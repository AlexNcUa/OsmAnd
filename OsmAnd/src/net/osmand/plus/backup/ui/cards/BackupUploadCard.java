package net.osmand.plus.backup.ui.cards;

import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.AndroidUtils;
import net.osmand.plus.R;
import net.osmand.plus.UiUtilities;
import net.osmand.plus.UiUtilities.DialogButtonType;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.backup.BackupDbHelper.UploadedFileInfo;
import net.osmand.plus.backup.BackupHelper;
import net.osmand.plus.backup.BackupHelper.BackupInfo;
import net.osmand.plus.backup.LocalFile;
import net.osmand.plus.backup.NetworkSettingsHelper.BackupExportListener;
import net.osmand.plus.backup.RemoteFile;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.routepreparationmenu.cards.BaseCard;
import net.osmand.plus.settings.backend.ExportSettingsType;
import net.osmand.plus.settings.backend.backup.items.FileSettingsItem;
import net.osmand.plus.settings.backend.backup.items.ProfileSettingsItem;
import net.osmand.plus.settings.backend.backup.items.SettingsItem;
import net.osmand.plus.settings.fragments.MainSettingsFragment;
import net.osmand.util.Algorithms;

import java.util.ArrayList;
import java.util.List;

import static net.osmand.plus.backup.ui.cards.LocalBackupCard.adjustIndicator;

public class BackupUploadCard extends BaseCard {

	private final BackupInfo info;
	private final BackupExportListener listener;

	private View actionButton;
	private View progressContainer;
	private TextView progressTitle;
	private ProgressBar progressBar;
	private ViewGroup itemsContainer;

	private boolean buttonsVisible = true;

	public BackupUploadCard(@NonNull MapActivity mapActivity, @NonNull BackupInfo info, @Nullable BackupExportListener listener) {
		super(mapActivity, false);
		this.info = info;
		this.listener = listener;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.backup_upload_card;
	}

	@Override
	protected void updateContent() {
		setupHeader();
		setupUploadItems();
		setupConflictingItems();
		setupActionButton();
		AndroidUiHelper.updateVisibility(actionButton, buttonsVisible);
		AndroidUiHelper.updateVisibility(itemsContainer, buttonsVisible);
	}

	private void setupHeader() {
		progressContainer = view.findViewById(R.id.upload_container);
		progressTitle = progressContainer.findViewById(R.id.title);
		progressBar = progressContainer.findViewById(R.id.progress_bar);

		progressTitle.setText(R.string.shared_string_items);
		ImageView icon = progressContainer.findViewById(R.id.icon);
		icon.setImageDrawable(getContentIcon(R.drawable.ic_action_cloud_upload));

		progressContainer.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				buttonsVisible = !buttonsVisible;
				adjustIndicator(app, buttonsVisible, view, nightMode);
				AndroidUiHelper.updateVisibility(actionButton, buttonsVisible);
				AndroidUiHelper.updateVisibility(itemsContainer, buttonsVisible);
			}
		});
		adjustIndicator(app, buttonsVisible, view, nightMode);

		setupSelectableBackground(progressContainer);
		AndroidUiHelper.updateVisibility(progressBar, app.getNetworkSettingsHelper().isBackupExporting());
	}

	private void setupUploadItems() {
		itemsContainer = view.findViewById(R.id.items_container);
		itemsContainer.removeAllViews();

		LayoutInflater themedInflater = UiUtilities.getInflater(view.getContext(), nightMode);
		for (LocalFile localFile : info.filesToUpload) {
			SettingsItem item = localFile.item;
			if (item == null) {
				continue;
			}
			View itemView = themedInflater.inflate(R.layout.backup_upload_item, itemsContainer, false);
			setupItemView(item, itemView);

			itemsContainer.addView(itemView);
			AndroidUiHelper.updateVisibility(itemView.findViewById(R.id.warningIcon), false);
		}
	}

	private void setupConflictingItems() {
		itemsContainer = view.findViewById(R.id.items_container);

		LayoutInflater themedInflater = UiUtilities.getInflater(view.getContext(), nightMode);
		for (Pair<LocalFile, RemoteFile> pair : info.filesToMerge) {
			SettingsItem item = pair.first.item;
			if (pair.first.item == null || pair.second.item == null) {
				continue;
			}
			View itemView = themedInflater.inflate(R.layout.backup_upload_item, itemsContainer, false);
			setupItemView(item, itemView);

			View localVersionButton = itemView.findViewById(R.id.local_version_button);
			localVersionButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					app.getNetworkSettingsHelper().exportSettings(listener, pair.first.item);
				}
			});
			View serverButton = itemView.findViewById(R.id.server_button);
			serverButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					app.getNetworkSettingsHelper().exportSettings(listener, pair.second.item);
				}
			});
			AndroidUiHelper.updateVisibility(serverButton, true);
			AndroidUiHelper.updateVisibility(localVersionButton, true);
			AndroidUiHelper.updateVisibility(itemView.findViewById(R.id.warningIcon), true);
			UiUtilities.setupDialogButton(nightMode, localVersionButton, DialogButtonType.SECONDARY, R.string.upload_local_version);
			UiUtilities.setupDialogButton(nightMode, serverButton, DialogButtonType.SECONDARY, R.string.download_server_version);
			AndroidUtils.setBackground(app, localVersionButton, nightMode, R.drawable.dlg_btn_transparent_light, R.drawable.dlg_btn_transparent_dark);
			AndroidUtils.setBackground(app, serverButton, nightMode, R.drawable.dlg_btn_transparent_light, R.drawable.dlg_btn_transparent_dark);

			itemsContainer.addView(itemView);
		}
	}

	private void setupItemView(SettingsItem item, View itemView) {
		TextView title = itemView.findViewById(R.id.title);
		if (item instanceof ProfileSettingsItem) {
			ProfileSettingsItem profileSettingsItem = (ProfileSettingsItem) item;
			title.setText(profileSettingsItem.getAppMode().toHumanString());
		} else if (item instanceof FileSettingsItem) {
			FileSettingsItem profileSettingsItem = (FileSettingsItem) item;
			title.setText(Algorithms.getFileWithoutDirs(profileSettingsItem.getFile().getName()));
		} else {
			title.setText(item.getName());
		}

		String filename = BackupHelper.getItemFileName(item);
		TextView description = itemView.findViewById(R.id.description);
		String summary = app.getString(R.string.last_backup);
		UploadedFileInfo info = app.getBackupHelper().getDbHelper().getUploadedFileInfo(item.getType().name(), filename);
		if (info != null) {
			String time = MainSettingsFragment.getLastBackupTimeDescription(app, info.getUploadTime(), app.getString(R.string.shared_string_never));
			description.setText(app.getString(R.string.ltr_or_rtl_combine_via_colon, summary, time));
		} else {
			description.setText(app.getString(R.string.ltr_or_rtl_combine_via_colon, summary, app.getString(R.string.shared_string_never)));
		}
		ImageView icon = itemView.findViewById(R.id.icon);
		icon.setImageDrawable(getIcon(item));
	}

	private void setupActionButton() {
		actionButton = view.findViewById(R.id.action_button);
		if (app.getNetworkSettingsHelper().isBackupExporting()) {
			UiUtilities.setupDialogButton(nightMode, actionButton, DialogButtonType.SECONDARY, R.string.shared_string_cancel);
			AndroidUtils.setBackground(app, actionButton, nightMode, R.drawable.dlg_btn_transparent_light, R.drawable.dlg_btn_transparent_dark);
			actionButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					app.getNetworkSettingsHelper().cancelExport();
				}
			});
		} else {
			UiUtilities.setupDialogButton(nightMode, actionButton, DialogButtonType.SECONDARY, R.string.backup_now);
			AndroidUtils.setBackground(app, actionButton, nightMode, R.drawable.dlg_btn_transparent_light, R.drawable.dlg_btn_transparent_dark);
			actionButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					List<SettingsItem> items = new ArrayList<>();
					for (LocalFile localFile : info.filesToUpload) {
						if (localFile.item != null) {
							items.add(localFile.item);
						}
					}
					if (!items.isEmpty()) {
						app.getNetworkSettingsHelper().exportSettings(listener, items);
					}
				}
			});
		}
	}

	public void updateProgress(int value) {
		String uploading = app.getString(R.string.local_openstreetmap_uploading);
		progressTitle.setText(app.getString(R.string.ltr_or_rtl_combine_via_space, uploading, String.valueOf(value)));

		progressBar.setProgress(value);
		AndroidUiHelper.updateVisibility(progressBar, app.getNetworkSettingsHelper().isBackupExporting());
	}

	private void setupSelectableBackground(View view) {
		Drawable drawable = UiUtilities.getColoredSelectableDrawable(app, getActiveColor(), 0.3f);
		AndroidUtils.setBackground(view, drawable);
	}

	private Drawable getIcon(SettingsItem item) {
		switch (item.getType()) {
			case GLOBAL:
				return getContentIcon(ExportSettingsType.GLOBAL.getIconRes());
			case PROFILE:
				ProfileSettingsItem profileSettingsItem = (ProfileSettingsItem) item;
				return getContentIcon(profileSettingsItem.getAppMode().getIconRes());
			case QUICK_ACTIONS:
				return getContentIcon(ExportSettingsType.QUICK_ACTIONS.getIconRes());
			case POI_UI_FILTERS:
				return getContentIcon(ExportSettingsType.POI_TYPES.getIconRes());
			case MAP_SOURCES:
				return getContentIcon(ExportSettingsType.MAP_SOURCES.getIconRes());
			case AVOID_ROADS:
				return getContentIcon(ExportSettingsType.AVOID_ROADS.getIconRes());
			case OSM_NOTES:
				return getContentIcon(ExportSettingsType.OSM_NOTES.getIconRes());
			case OSM_EDITS:
				return getContentIcon(ExportSettingsType.OSM_EDITS.getIconRes());
			case FAVOURITES:
				return getContentIcon(ExportSettingsType.FAVORITES.getIconRes());
			case ACTIVE_MARKERS:
				return getContentIcon(ExportSettingsType.ACTIVE_MARKERS.getIconRes());
			case HISTORY_MARKERS:
				return getContentIcon(ExportSettingsType.HISTORY_MARKERS.getIconRes());
			case SEARCH_HISTORY:
				return getContentIcon(ExportSettingsType.SEARCH_HISTORY.getIconRes());
			case GPX:
				return getContentIcon(ExportSettingsType.TRACKS.getIconRes());
			case ONLINE_ROUTING_ENGINES:
				return getContentIcon(ExportSettingsType.ONLINE_ROUTING_ENGINES.getIconRes());
			case ITINERARY_GROUPS:
				return getContentIcon(ExportSettingsType.ITINERARY_GROUPS.getIconRes());
		}
		return null;
	}
}