package net.osmand.plus.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.backup.NetworkWriter.OnUploadItemListener;
import net.osmand.plus.settings.backend.ExportSettingsType;
import net.osmand.plus.settings.backend.backup.Exporter;
import net.osmand.plus.settings.backend.backup.items.SettingsItem;
import net.osmand.util.Algorithms;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BackupExporter extends Exporter {

	private final BackupHelper backupHelper;
	private final NetworkExportProgressListener listener;

	public interface NetworkExportProgressListener {
		void updateItemProgress(@NonNull String type, @NonNull String fileName, int value);

		void updateGeneralProgress(int uploadedItems, int uploadedKb);

		void networkExportDone(@NonNull Map<String, String> errors);
	}

	BackupExporter(@NonNull BackupHelper backupHelper, @Nullable NetworkExportProgressListener listener) {
		super(null);
		this.backupHelper = backupHelper;
		this.listener = listener;
	}

	@Override
	public void export() throws IOException {
		writeItems();
	}

	private void writeItems() throws IOException {
		Map<String, String> errors = new HashMap<>();
		OnUploadItemListener uploadItemListener = new OnUploadItemListener() {
			final Set<SettingsItem> itemsProgress = new HashSet<>();
			final int[] dataProgress = {0};

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
					listener.updateGeneralProgress(itemsProgress.size(), dataProgress[0]);
				}
			}
		};
		NetworkWriter networkWriter = new NetworkWriter(backupHelper, uploadItemListener);
		writeItems(networkWriter);
		if (!isCancelled()) {
			backupHelper.updateBackupUploadTime();
		}
		if (listener != null) {
			listener.networkExportDone(errors);
		}
	}

	private void checkAndDeleteOldFile(@NonNull SettingsItem item, @NonNull String fileName, Map<String, String> errors) {
		String type = item.getType().name();
		try {
			ExportSettingsType exportType = ExportSettingsType.getExportSettingsTypeForItem(item);
			if (exportType != null && !backupHelper.getVersionHistoryTypePref(exportType).get()) {
				RemoteFile remoteFile = getRemoteFile(type, fileName);
				if (remoteFile != null) {
					backupHelper.deleteFiles(Collections.singletonList(remoteFile), true, null);
				}
			}
		} catch (UserNotRegisteredException e) {
			errors.put(type + "/" + fileName, e.getMessage());
		}
	}

	@Nullable
	private RemoteFile getRemoteFile(@NonNull String type, @NonNull String fileName) {
		List<RemoteFile> remoteFiles = backupHelper.getBackup().getAllRemoteFiles();
		if (!Algorithms.isEmpty(fileName) && !Algorithms.isEmpty(remoteFiles)) {
			for (RemoteFile remoteFile : remoteFiles) {
				if (remoteFile.getType().equals(type) && remoteFile.getName().equals(fileName)) {
					return remoteFile;
				}
			}
		}
		return null;
	}
}
