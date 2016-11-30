package de.westnordost.streetcomplete.data;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import de.westnordost.streetcomplete.ApplicationConstants;
import de.westnordost.streetcomplete.data.download.QuestAutoDownloadStrategy;
import de.westnordost.streetcomplete.data.download.QuestDownloadService;
import de.westnordost.streetcomplete.data.osm.OsmQuest;
import de.westnordost.streetcomplete.data.osm.changes.StringMapChanges;
import de.westnordost.streetcomplete.data.osm.changes.StringMapChangesBuilder;
import de.westnordost.streetcomplete.data.osm.persist.ElementGeometryDao;
import de.westnordost.streetcomplete.data.osm.persist.MergedElementDao;
import de.westnordost.streetcomplete.data.osm.persist.OsmQuestDao;
import de.westnordost.streetcomplete.data.osmnotes.CreateNote;
import de.westnordost.streetcomplete.data.osmnotes.CreateNoteDao;
import de.westnordost.streetcomplete.data.osmnotes.OsmNoteQuest;
import de.westnordost.streetcomplete.data.osmnotes.OsmNoteQuestDao;
import de.westnordost.streetcomplete.quests.note_discussion.NoteDiscussionForm;
import de.westnordost.streetcomplete.util.SlippyMapMath;
import de.westnordost.osmapi.map.data.BoundingBox;
import de.westnordost.osmapi.map.data.Element;
import de.westnordost.osmapi.map.data.LatLon;

import static android.content.Context.BIND_AUTO_CREATE;

public class QuestController
{
	private final OsmQuestDao osmQuestDB;
	private final MergedElementDao osmElementDB;
	private final ElementGeometryDao geometryDB;
	private final OsmNoteQuestDao osmNoteQuestDB;
	private final CreateNoteDao createNoteDB;
	private final Context context;
	private final ExecutorService questRetriever;
	private final VisibleQuestRelay relay;

	private QuestAutoDownloadStrategy downloadStrategy;

	private boolean downloadServiceIsBound;
	private QuestDownloadService.Interface downloadService;
	private ServiceConnection downloadServiceConnection = new ServiceConnection()
	{
		public void onServiceConnected(ComponentName className, IBinder service)
		{
			downloadService = ((QuestDownloadService.Interface)service);
			downloadService.setQuestListener(relay);
		}

		public void onServiceDisconnected(ComponentName className)
		{
			downloadService = null;
		}
	};

	@Inject public QuestController(OsmQuestDao osmQuestDB, MergedElementDao osmElementDB,
								   ElementGeometryDao geometryDB, OsmNoteQuestDao osmNoteQuestDB,
								   CreateNoteDao createNoteDB, Context context)
	{
		this.osmQuestDB = osmQuestDB;
		this.osmElementDB = osmElementDB;
		this.geometryDB = geometryDB;
		this.osmNoteQuestDB = osmNoteQuestDB;
		this.createNoteDB = createNoteDB;
		this.context = context;
		this.relay = new VisibleQuestRelay();
		questRetriever = Executors.newSingleThreadExecutor();
	}

	public void onStart(VisibleQuestListener questListener)
	{
		relay.setListener(questListener);
		downloadServiceIsBound = context.bindService(
				new Intent(context, QuestDownloadService.class),
				downloadServiceConnection, BIND_AUTO_CREATE);
	}

	public void onStop()
	{
		relay.setListener(null);
		if(downloadServiceIsBound) context.unbindService(downloadServiceConnection);
		if(downloadService != null) downloadService.setQuestListener(null);
	}

	public QuestAutoDownloadStrategy getDownloadStrategy()
	{
		return downloadStrategy;
	}

	public void setDownloadStrategy(QuestAutoDownloadStrategy downloadStrategy)
	{
		this.downloadStrategy = downloadStrategy;
	}

	/** Create a note for the given OSM Quest instead of answering it. The quest will turn
	 *  invisible. */
	public void createNote(final long osmQuestId, final String text)
	{
		new Thread() { @Override public void run()
		{
			OsmQuest q = osmQuestDB.get(osmQuestId);

			CreateNote createNote = new CreateNote();
			createNote.position = q.getMarkerLocation();
			createNote.text = text;
			createNote.elementType = q.getElementType();
			createNote.elementId = q.getElementId();
			createNoteDB.add(createNote);

			/** The quests that reference the same element for which the user was not able to
			 *  answer the question are removed because the to-be-created note blocks quest
			 *  creation for other users, so those quests should be removed from the user's
			 *  own display as well. As soon as the note is resolved, the quests will be re-
			 *  created next time they are downloaded */
			List<OsmQuest> quests = osmQuestDB.getAll(null, QuestStatus.NEW, null,
					q.getElementType(), q.getElementId());
			for(OsmQuest quest : quests)
			{
				osmQuestDB.delete(quest.getId());
				relay.onOsmQuestRemoved(quest.getId());
			}
			osmElementDB.deleteUnreferenced();
			geometryDB.deleteUnreferenced();

			// try to upload directly
			upload();
		}}.start();
	}

	/** Apply the user's answer to the given quest. (The quest will turn invisible.) */
	public void solveQuest(final long questId, final QuestGroup group, final Bundle answer)
	{
		new Thread() { @Override public void run()
		{
			if (group == QuestGroup.OSM)
			{
				OsmQuest q = osmQuestDB.get(questId);
				Element e = osmElementDB.get(q.getElementType(), q.getElementId());
				StringMapChangesBuilder changesBuilder = new StringMapChangesBuilder(e.getTags());
				q.getOsmElementQuestType().applyAnswerTo(answer, changesBuilder);
				StringMapChanges changes = changesBuilder.create();
				if(!changes.isEmpty())
				{
					q.setChanges(changes);
					q.setStatus(QuestStatus.ANSWERED);
					osmQuestDB.update(q);
					relay.onOsmQuestRemoved(q.getId());
				}
				else
				{
					throw new RuntimeException(
							"OsmQuest " + questId + " (" + q.getType().getClass().getSimpleName() +
							") has been answered by the user but the changeset is empty!");
				}
			}
			else if (group == QuestGroup.OSM_NOTE)
			{
				OsmNoteQuest q = osmNoteQuestDB.get(questId);
				String comment = answer.getString(NoteDiscussionForm.TEXT);
				if(comment != null && !comment.isEmpty())
				{
					q.setComment(comment);
					q.setStatus(QuestStatus.ANSWERED);
					osmNoteQuestDB.update(q);
					relay.onNoteQuestRemoved(q.getId());
				}
				else
				{
					throw new RuntimeException(
							"NoteQuest has been answered with an empty comment!");
				}
			}
			// try to upload directly
			upload();
		}}.start();
	}

	/** Make the given quest invisible asynchronously (per user interaction). */
	public void hideQuest(final long questId, final QuestGroup group)
	{
		new Thread() { @Override public void run()
		{
			if(group == QuestGroup.OSM)
			{
				OsmQuest q = osmQuestDB.get(questId);
				q.setStatus(QuestStatus.HIDDEN);
				osmQuestDB.update(q);
				relay.onOsmQuestRemoved(q.getId());
			}
			else if(group == QuestGroup.OSM_NOTE)
			{
				OsmNoteQuest q = osmNoteQuestDB.get(questId);
				q.setStatus(QuestStatus.HIDDEN);
				osmNoteQuestDB.update(q);
				relay.onNoteQuestRemoved(q.getId());
			}
		}}.start();
	}

	/** Retrieve the given quest from local database asynchronously, including the element / note. */
	public void retrieve(final QuestGroup group, final long questId)
	{
		questRetriever.submit(new Runnable() { @Override public void run()
		{
			switch (group)
			{
				case OSM:
					OsmQuest osmQuest = osmQuestDB.get(questId);
					Element element = osmElementDB.get(osmQuest.getElementType(), osmQuest.getElementId());
					relay.onQuestCreated(osmQuest, element);
					break;
				case OSM_NOTE:
					OsmNoteQuest osmNoteQuest = osmNoteQuestDB.get(questId);
					relay.onQuestCreated(osmNoteQuest);
					break;
			}
		}});
	}

	/** Retrieve all visible (=new) quests in the given bounding box from local database
	 *  asynchronously. */
	public void retrieve(final BoundingBox bbox)
	{
		questRetriever.submit(new Runnable() { @Override public void run()
		{
			for (OsmQuest q : osmQuestDB.getAll(bbox, QuestStatus.NEW))
			{
				relay.onQuestCreated(q, null);
			}
			for (OsmNoteQuest q : osmNoteQuestDB.getAll(bbox, QuestStatus.NEW))
			{
				relay.onQuestCreated(q);
			}
		}});
	}

	/** Download quests in at least the given bounding box asynchronously. The next-bigger rectangle
	 *  in a (z14) tiles grid that encloses the given bounding box will be downloaded.
	 *
	 *  Multiple calls to this method will cancel the previous download job.
	 *
	 *  @param bbox the minimum area to download
	 */
	public void manualDownload(final BoundingBox bbox)
	{
		download(bbox, null, true);
	}

	private void download(BoundingBox bbox, Integer maxQuestTypesToDownload, boolean manualStart)
	{
		Rect tilesRect = SlippyMapMath.enclosingTiles(bbox, ApplicationConstants.QUEST_TILE_ZOOM);

		Intent intent = new Intent(context, QuestDownloadService.class);
		intent.putExtra(QuestDownloadService.ARG_TILES_RECT, tilesRect);

		if(maxQuestTypesToDownload != null)
		{
			intent.putExtra(QuestDownloadService.ARG_MAX_QUEST_TYPES, maxQuestTypesToDownload);
		}
		if(manualStart)
		{
			intent.putExtra(QuestDownloadService.ARG_MANUAL, true);
		}
		context.startService(intent);

	}

	public void autoDownload(final LatLon center)
	{
		// do not disrupt a download triggered by a user
		if(isCurrentDownloadRunningAndStartedByUser()) return;
		// no auto download
		if(downloadStrategy == null) return;

		new Thread(){ @Override public void run() {

			if(!downloadStrategy.mayDownloadHere(center)) return;

			download(
					downloadStrategy.getDownloadBoundingBox(center),
					downloadStrategy.getQuestTypeDownloadCount(), false);
		}}.start();
	}

	/** @return true if a quest download triggered by the user is running */
	public boolean isCurrentDownloadRunningAndStartedByUser()
	{
		return downloadService != null &&
		       downloadService.isDownloading() &&
		       downloadService.isCurrentDownloadStartedByUser();
	}

	/** Collect and upload all changes made by the user */
	public void upload()
	{
		context.startService(new Intent(context, QuestChangesUploadService.class));
	}
}
