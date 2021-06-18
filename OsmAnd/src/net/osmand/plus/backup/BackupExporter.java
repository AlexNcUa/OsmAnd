package net.osmand.plus.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.backup.BackupHelper.OnDeleteFilesListener;
import net.osmand.plus.backup.NetworkWriter.OnUploadItemListener;
import net.osmand.plus.settings.backend.ExportSettingsType;
import net.osmand.plus.settings.backend.backup.Exporter;
import net.osmand.plus.settings.backend.backup.items.SettingsItem;
import net.osmand.util.Algorithms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class BackupExporter extends Exporter {

	private final BackupHelper backupHelper;
	private final Map<String, RemoteFile> filesToDelete = new LinkedHashMap<>();
	private final NetworkExportProgressListener listener;

	public interface NetworkExportProgressListener {
		void itemExportStarted(@NonNull String type, @NonNull String fileName, int work);

		void updateItemProgress(@NonNull String type, @NonNull String fileName, int progress);

		void itemExportDone(@NonNull String type, @NonNull String fileName);

		void updateGeneralProgress(int uploadedItems, int uploadedKb);

		void networkExportDone(@NonNull Map<String, String> errors);
	}

	BackupExporter(@NonNull BackupHelper backupHelper, @Nullable NetworkExportProgressListener listener) {
		super(null);
		this.backupHelper = backupHelper;
		this.listener = listener;
	}

	public Map<String, RemoteFile> getFilesToDelete() {
		return filesToDelete;
	}

	public void addFileToDelete(RemoteFile file) throws IllegalArgumentException {
		if (filesToDelete.containsKey(file.getTypeNamePath())) {
			throw new IllegalArgumentException("Already has such file: " + file.getTypeNamePath());
		}
		filesToDelete.put(file.getName(), file);
	}

	@Override
	public void export() throws IOException {
		exportItems();
	}

	private void exportItems() throws IOException {
		int[] dataProgress = {0};
		Set<Object> itemsProgress = new HashSet<>();
		Map<String, String> errors = new HashMap<>();

		OnUploadItemListener uploadItemListener = getOnUploadItemListener(itemsProgress, dataProgress, errors);
		OnDeleteFilesListener deleteFilesListener = getOnDeleteFilesListener(itemsProgress, dataProgress, errors);

		NetworkWriter networkWriter = new NetworkWriter(backupHelper, uploadItemListener);
		writeItems(networkWriter);
		deleteFiles(deleteFilesListener);
		if (!isCancelled()) {
			backupHelper.updateBackupUploadTime();
		}
		if (listener != null) {
			listener.networkExportDone(errors);
		}
	}

	protected void deleteFiles(OnDeleteFilesListener listener) throws IOException {
		try {
			backupHelper.deleteFiles(new ArrayList<>(getFilesToDelete().values()), listener);
		} catch (UserNotRegisteredException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	private OnUploadItemListener getOnUploadItemListener(Set<Object> itemsProgress, int[] dataProgress, Map<String, String> errors) {
		return new OnUploadItemListener() {

			@Override
			public void onItemFileUploadStarted(@NonNull SettingsItem item, @NonNull String fileName, int work) {
				if (listener != null) {
					listener.itemExportStarted(item.getType().name(), fileName, work);
				}
			}

			@Override
			public void onItemFileUploadProgress(@NonNull SettingsItem item, @NonNull String fileName, int progress, int deltaWork) {
				dataProgress[0] += deltaWork;
				if (listener != null) {
					listener.updateItemProgress(item.getType().name(), fileName, progress);
					listener.updateGeneralProgress(itemsProgress.size(), dataProgress[0]);
				}
			}

			@Override
			public void onItemFileUploadDone(@NonNull SettingsItem item, @NonNull String fileName, long uploadTime, @Nullable String error) {
				String type = item.getType().name();
				if (!Algorithms.isEmpty(error)) {
					errors.put(type + "/" + fileName, error);
				} else {
					checkAndDeleteOldFile(item, fileName, errors);
				}
				itemsProgress.add(item);
				if (listener != null) {
					listener.itemExportDone(item.getType().name(), fileName);
					listener.updateGeneralProgress(itemsProgress.size(), dataProgress[0]);
				}
			}
		};
	}

	private OnDeleteFilesListener getOnDeleteFilesListener(Set<Object> itemsProgress, int[] dataProgress, Map<String, String> errors) {
		return new OnDeleteFilesListener() {

			@Override
			public void onFileDeleteProgress(@NonNull RemoteFile file) {
				itemsProgress.add(file);
				if (listener != null) {
					listener.itemExportDone(file.getType(), file.getName());
					listener.updateGeneralProgress(itemsProgress.size(), dataProgress[0]);
				}
			}

			@Override
			public void onFilesDeleteDone(@NonNull Map<RemoteFile, String> errors) {

			}

			@Override
			public void onFilesDeleteError(int status, @NonNull String message) {

			}
		};
	}

	private void checkAndDeleteOldFile(@NonNull SettingsItem item, @NonNull String fileName, Map<String, String> errors) {
		PrepareBackupResult backup = backupHelper.getBackup();
		if (backup != null) {
			String type = item.getType().name();
			try {
				ExportSettingsType exportType = ExportSettingsType.getExportSettingsTypeForItem(item);
				if (exportType != null && !backupHelper.getVersionHistoryTypePref(exportType).get()) {
					RemoteFile remoteFile = backup.getRemoteFile(type, fileName);
					if (remoteFile != null) {
						backupHelper.deleteFiles(Collections.singletonList(remoteFile), true, null);
					}
				}
			} catch (UserNotRegisteredException e) {
				errors.put(type + "/" + fileName, e.getMessage());
			}
		}
	}
}
