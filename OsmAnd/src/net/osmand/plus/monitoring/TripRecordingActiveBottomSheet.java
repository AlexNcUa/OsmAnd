package net.osmand.plus.monitoring;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import net.osmand.AndroidUtils;
import net.osmand.GPXUtilities.GPXFile;
import net.osmand.PlatformUtil;
import net.osmand.plus.GpxSelectionHelper.SelectedGpxFile;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.UiUtilities;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.activities.SavingTrackHelper;
import net.osmand.plus.activities.SavingTrackHelper.SaveGpxResult;
import net.osmand.plus.base.MenuBottomSheetDialogFragment;
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.helpers.FontCache;
import net.osmand.plus.myplaces.SaveCurrentTrackTask;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.track.GpxBlockStatisticsBuilder;
import net.osmand.plus.track.SaveGpxAsyncTask.SaveGpxListener;
import net.osmand.plus.track.TrackAppearanceFragment;
import net.osmand.plus.widgets.TextViewEx;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;

import static net.osmand.plus.UiUtilities.CompoundButtonType.PROFILE_DEPENDENT;

public class TripRecordingActiveBottomSheet extends MenuBottomSheetDialogFragment {

	public static final String TAG = TripRecordingActiveBottomSheet.class.getSimpleName();
	private static final Log log = PlatformUtil.getLog(TripRecordingActiveBottomSheet.class);
	private static final String UPDATE_CURRENT_GPX_FILE = "update_current_gpx_file";
	private static final int GENERAL_UPDATE_GPS_INTERVAL = 1000;
	private static final int GENERAL_UPDATE_SAVE_INTERVAL = 1000;

	private OsmandApplication app;
	private OsmandSettings settings;
	private SavingTrackHelper helper;
	private SelectedGpxFile selectedGpxFile;

	private View statusContainer;
	private LinearLayout buttonAppearance;
	private View buttonSave;
	private GpxBlockStatisticsBuilder blockStatisticsBuilder;

	private final Handler handler = new Handler();
	private Runnable updatingGPS;
	private Runnable updatingTimeTrackSaved;

	private GPXFile getGPXFile() {
		return selectedGpxFile.getGpxFile();
	}

	public void setSelectedGpxFile(SelectedGpxFile selectedGpxFile) {
		this.selectedGpxFile = selectedGpxFile;
	}

	public boolean hasDataToSave() {
		return app.getSavingTrackHelper().hasDataToSave();
	}

	public boolean searchingGPS() {
		return app.getLocationProvider().getLastKnownLocation() == null;
	}

	public boolean wasTrackMonitored() {
		return settings.SAVE_GLOBAL_TRACK_TO_GPX.get();
	}

	public static void showInstance(@NonNull FragmentManager fragmentManager, SelectedGpxFile selectedGpxFile) {
		if (!fragmentManager.isStateSaved()) {
			TripRecordingActiveBottomSheet fragment = new TripRecordingActiveBottomSheet();
			fragment.setSelectedGpxFile(selectedGpxFile);
			fragment.show(fragmentManager, TAG);
		}
	}

	@Override
	public void createMenuItems(Bundle savedInstanceState) {
		app = requiredMyApplication();
		settings = app.getSettings();
		helper = app.getSavingTrackHelper();
		LayoutInflater inflater = UiUtilities.getInflater(getContext(), nightMode);
		final FragmentManager fragmentManager = getFragmentManager();

		View itemView = inflater.inflate(R.layout.trip_recording_active_fragment, null, false);
		items.add(new BaseBottomSheetItem.Builder()
				.setCustomView(itemView)
				.create());

		View buttonClear = itemView.findViewById(R.id.button_clear);
		final View buttonOnline = itemView.findViewById(R.id.button_online);
		View buttonSegment = itemView.findViewById(R.id.button_segment);
		buttonSave = itemView.findViewById(R.id.button_save);
		final View buttonPause = itemView.findViewById(R.id.button_pause);
		View buttonStop = itemView.findViewById(R.id.button_stop);

		createItem(buttonClear, ItemType.CLEAR_DATA, hasDataToSave());
		createItem(buttonOnline, ItemType.STOP_ONLINE, hasDataToSave());
		createItem(buttonSegment, ItemType.START_SEGMENT, wasTrackMonitored());
		createItem(buttonPause, wasTrackMonitored() ? ItemType.PAUSE : ItemType.RESUME, true);
		createItem(buttonStop, ItemType.STOP, true);

		AndroidUiHelper.updateVisibility(buttonOnline, app.getLiveMonitoringHelper().isLiveMonitoringEnabled());

		statusContainer = itemView.findViewById(R.id.status_container);
		updateStatus();

		RecyclerView statBlocks = itemView.findViewById(R.id.block_statistics);
		if (savedInstanceState != null) {
			if (savedInstanceState.containsKey(UPDATE_CURRENT_GPX_FILE)
					&& savedInstanceState.getBoolean(UPDATE_CURRENT_GPX_FILE)) {
				selectedGpxFile = app.getSavingTrackHelper().getCurrentTrack();
			}
		}
		blockStatisticsBuilder = new GpxBlockStatisticsBuilder(app, selectedGpxFile);
		blockStatisticsBuilder.setBlocksView(statBlocks);
		blockStatisticsBuilder.setBlocksClickable(false);
		blockStatisticsBuilder.initStatBlocks(null, ContextCompat.getColor(app, getActiveTextColorId(nightMode)), nightMode);

		LinearLayout showTrackContainer = itemView.findViewById(R.id.show_track_on_map);
		showTrackContainer.setMinimumHeight(app.getResources().getDimensionPixelSize(R.dimen.bottom_sheet_list_item_height));

		final LinearLayout buttonShow = showTrackContainer.findViewById(R.id.basic_item_body);
		TextView showTrackTitle = buttonShow.findViewById(R.id.title);
		Integer showTitle = ItemType.SHOW_TRACK.getTitleId();
		if (showTitle != null) {
			showTrackTitle.setText(showTitle);
		}
		AndroidUtils.setPadding(buttonShow, AndroidUtils.dpToPx(app, 12f), 0, buttonShow.getPaddingRight(), 0);
		showTrackTitle.setTextColor(ContextCompat.getColor(app, getActiveIconColorId(nightMode)));
		showTrackTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.default_desc_text_size));
		Typeface typeface = FontCache.getFont(app, app.getResources().getString(R.string.font_roboto_medium));
		showTrackTitle.setTypeface(typeface);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			float letterSpacing = AndroidUtils.getFloatValueFromRes(app, R.dimen.text_button_letter_spacing);
			showTrackTitle.setLetterSpacing(letterSpacing);
		}
		final SwitchCompat showTrackOnMapButton = buttonShow.findViewById(R.id.switch_button);
		showTrackOnMapButton.setChecked(app.getSelectedGpxHelper().getSelectedCurrentRecordingTrack() != null);
		UiUtilities.setupCompoundButton(showTrackOnMapButton, nightMode, PROFILE_DEPENDENT);

		buttonAppearance = showTrackContainer.findViewById(R.id.additional_button);
		View divider = buttonAppearance.getChildAt(0);
		AndroidUiHelper.setVisibility(View.GONE, divider);
		int marginS = app.getResources().getDimensionPixelSize(R.dimen.context_menu_padding_margin_small);
		UiUtilities.setMargins(buttonAppearance, marginS, 0, 0, 0);
		updateTrackIcon(buttonAppearance);
		buttonAppearance.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (showTrackOnMapButton.isChecked()) {
					MapActivity mapActivity = getMapActivity();
					if (mapActivity != null) {
						hide();
						SelectedGpxFile selectedGpxFile = app.getSavingTrackHelper().getCurrentTrack();
						TrackAppearanceFragment.showInstance(mapActivity, selectedGpxFile, TripRecordingActiveBottomSheet.this);
					}
				}
			}
		});
		createItem(buttonAppearance, ItemType.APPEARANCE, showTrackOnMapButton.isChecked());
		setShowOnMapBackground(buttonShow, app, showTrackOnMapButton.isChecked(), nightMode);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			buttonShow.setBackgroundTintList(null);
		}
		buttonShow.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				boolean checked = !showTrackOnMapButton.isChecked();
				showTrackOnMapButton.setChecked(checked);
				app.getSelectedGpxHelper().selectGpxFile(app.getSavingTrackHelper().getCurrentGpx(), checked, false);
				createItem(buttonAppearance, ItemType.APPEARANCE, checked);
				setShowOnMapBackground(buttonShow, app, checked, nightMode);
			}
		});

		buttonClear.findViewById(R.id.button_container).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (fragmentManager != null && hasDataToSave()) {
					ClearRecordedDataBottomSheetFragment.showInstance(fragmentManager, TripRecordingActiveBottomSheet.this);
				}
			}
		});

		buttonOnline.findViewById(R.id.button_container).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				settings.LIVE_MONITORING.set(false);
				AndroidUiHelper.updateVisibility(buttonOnline, false);
			}
		});

		buttonSegment.findViewById(R.id.button_container).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (wasTrackMonitored()) {
					blockStatisticsBuilder.stopUpdatingStatBlocks();
					helper.startNewSegment();
					blockStatisticsBuilder.runUpdatingStatBlocksIfNeeded();
				}
			}
		});

		buttonSave.findViewById(R.id.button_container).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (hasDataToSave()) {
					final GPXFile gpxFile = getGPXFile();
					new SaveCurrentTrackTask(app, gpxFile, createSaveListener(new Runnable() {
						@Override
						public void run() {
							blockStatisticsBuilder.stopUpdatingStatBlocks();
							blockStatisticsBuilder.runUpdatingStatBlocksIfNeeded();
							stopUpdatingTimeTrackSaved();
							runUpdatingTimeTrackSaved();
						}
					})).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
				}
			}
		});

		buttonPause.findViewById(R.id.button_container).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				boolean wasTrackMonitored = !wasTrackMonitored();
				if (!wasTrackMonitored) {
					blockStatisticsBuilder.stopUpdatingStatBlocks();
				} else {
					blockStatisticsBuilder.runUpdatingStatBlocksIfNeeded();
				}
				settings.SAVE_GLOBAL_TRACK_TO_GPX.set(wasTrackMonitored);
				updateStatus();
				createItem(buttonPause, wasTrackMonitored ? ItemType.PAUSE : ItemType.RESUME, true);
			}
		});

		buttonStop.findViewById(R.id.button_container).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (fragmentManager != null) {
					StopTrackRecordingBottomFragment.showInstance(getMapActivity(), fragmentManager, TripRecordingActiveBottomSheet.this);
				}
			}
		});
	}

	private void updateStatus() {
		TextView statusTitle = statusContainer.findViewById(R.id.text_status);
		AppCompatImageView statusIcon = statusContainer.findViewById(R.id.icon_status);
		ItemType status = searchingGPS() ? ItemType.SEARCHING_GPS : !wasTrackMonitored() ? ItemType.ON_PAUSE : ItemType.RECORDING;
		Integer titleId = status.getTitleId();
		if (titleId != null) {
			statusTitle.setText(titleId);
		}
		int colorText = status.equals(ItemType.SEARCHING_GPS) ? getSecondaryTextColorId(nightMode) : getOsmandIconColorId(nightMode);
		statusTitle.setTextColor(ContextCompat.getColor(app, colorText));
		Integer iconId = status.getIconId();
		if (iconId != null) {
			int colorDrawable = ContextCompat.getColor(app,
					status.equals(ItemType.SEARCHING_GPS) ? getSecondaryIconColorId(nightMode) : getOsmandIconColorId(nightMode));
			Drawable statusDrawable = UiUtilities.tintDrawable(AppCompatResources.getDrawable(app, iconId), colorDrawable);
			statusIcon.setImageDrawable(statusDrawable);
		}
	}

	private void updateTrackIcon(View buttonAppearance) {
		String width = settings.CURRENT_TRACK_WIDTH.get();
		boolean showArrows = settings.CURRENT_TRACK_SHOW_ARROWS.get();
		int color = settings.CURRENT_TRACK_COLOR.get();
		Drawable appearanceDrawable = TrackAppearanceFragment.getTrackIcon(app, width, showArrows, color);
		AppCompatImageView appearanceIcon = buttonAppearance.findViewById(R.id.icon_after_divider);
		int marginTrackIconH = app.getResources().getDimensionPixelSize(R.dimen.content_padding_small);
		UiUtilities.setMargins(appearanceIcon, marginTrackIconH, 0, marginTrackIconH, 0);
		appearanceIcon.setImageDrawable(appearanceDrawable);
	}

	private void createItem(View view, ItemType type, boolean enabled) {
		createItem(app, nightMode, view, type, enabled, null);
	}

	private void createItem(View view, ItemType type, boolean enabled, @Nullable String description) {
		createItem(app, nightMode, view, type, enabled, description);
	}

	public static View createItem(Context context, boolean nightMode, LayoutInflater inflater, ItemType type) {
		View button = inflater.inflate(R.layout.bottom_sheet_button_with_icon, null);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
		int horizontal = context.getResources().getDimensionPixelSize(R.dimen.content_padding);
		params.setMargins(horizontal, 0, horizontal, 0);
		button.setLayoutParams(params);
		LinearLayout container = button.findViewById(R.id.button_container);
		container.setClickable(false);
		container.setFocusable(false);
		createItem(context, nightMode, button, type, true, null);
		return button;
	}

	public static void createItem(Context context, boolean nightMode, View view, ItemType type, boolean enabled, @Nullable String description) {
		view.setTag(type);
		LinearLayout button = view.findViewById(R.id.button_container);

		AppCompatImageView icon = view.findViewById(R.id.icon);
		if (icon != null) {
			setTintedIcon(context, icon, enabled, nightMode, type);
		}

		TextView title = view.findViewById(R.id.button_text);
		Integer titleId = type.getTitleId();
		if (title != null && titleId != null) {
			title.setText(titleId);
			setTextColor(context, title, enabled, nightMode, type);
		}

		TextViewEx desc = view.findViewById(R.id.desc);
		if (desc != null) {
			boolean isShowDesc = !Algorithms.isBlank(description);
			int marginDesc = isShowDesc ? 0 : context.getResources().getDimensionPixelSize(R.dimen.context_menu_padding_margin_medium);
			AndroidUiHelper.updateVisibility(desc, isShowDesc);
			if (title != null) {
				UiUtilities.setMargins(title, 0, marginDesc, 0, marginDesc);
			}
			desc.setText(description);
			setTextColor(context, desc, false, nightMode, type);
		}

		setItemBackground(context, nightMode, button != null ? button : (LinearLayout) view, enabled);
	}

	private String getTimeTrackSaved() {
		long timeTrackSaved = helper.getLastTimeFileSaved();
		if (timeTrackSaved != 0) {
			long now = System.currentTimeMillis();
			CharSequence time = DateUtils.getRelativeTimeSpanString(timeTrackSaved, now, DateUtils.MINUTE_IN_MILLIS);
			return String.valueOf(time);
		} else {
			return null;
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean(UPDATE_CURRENT_GPX_FILE, true);
	}

	@Override
	public void onResume() {
		super.onResume();
		blockStatisticsBuilder.runUpdatingStatBlocksIfNeeded();
		runUpdatingGPS();
		runUpdatingTimeTrackSaved();
	}

	@Override
	public void onPause() {
		super.onPause();
		blockStatisticsBuilder.stopUpdatingStatBlocks();
		stopUpdatingGPS();
		stopUpdatingTimeTrackSaved();
	}

	public void stopUpdatingGPS() {
		handler.removeCallbacks(updatingGPS);
	}

	public void runUpdatingGPS() {
		updatingGPS = new Runnable() {
			@Override
			public void run() {
				int interval = app.getSettings().SAVE_GLOBAL_TRACK_INTERVAL.get();
				updateStatus();
				handler.postDelayed(this, Math.max(GENERAL_UPDATE_GPS_INTERVAL, interval));
			}
		};
		handler.post(updatingGPS);
	}

	public void stopUpdatingTimeTrackSaved() {
		handler.removeCallbacks(updatingTimeTrackSaved);
	}

	public void runUpdatingTimeTrackSaved() {
		updatingTimeTrackSaved = new Runnable() {
			@Override
			public void run() {
				String time = getTimeTrackSaved();
				createItem(buttonSave, ItemType.SAVE, hasDataToSave(), !Algorithms.isEmpty(time) ? time : null);
				handler.postDelayed(this, GENERAL_UPDATE_SAVE_INTERVAL);
			}
		};
		handler.post(updatingTimeTrackSaved);
	}

	private SaveGpxListener createSaveListener(@Nullable final Runnable callback) {
		return new SaveGpxListener() {

			@Override
			public void gpxSavingStarted() {
			}

			@Override
			public void gpxSavingFinished(Exception errorMessage) {
				String gpxFileName = Algorithms.getFileWithoutDirs(getGPXFile().path);
				final MapActivity mapActivity = getMapActivity();
				final Context context = getContext();
				final SaveGpxResult result = helper.saveDataToGpx(app.getAppCustomization().getTracksDir());
				if (mapActivity != null && context != null) {
					final WeakReference<MapActivity> mapActivityRef = new WeakReference<>(mapActivity);
					final FragmentManager fragmentManager = mapActivityRef.get().getSupportFragmentManager();
					@SuppressLint({"StringFormatInvalid", "LocalSuppress"})
					Snackbar snackbar = Snackbar.make(getView(),
							app.getResources().getString(R.string.shared_string_file_is_saved, gpxFileName),
							Snackbar.LENGTH_LONG)
							.setAction(R.string.shared_string_rename, new View.OnClickListener() {
								@Override
								public void onClick(View view) {
									fragmentManager.beginTransaction().remove(TripRecordingActiveBottomSheet.this).commitAllowingStateLoss();
									SaveGPXBottomSheetFragment.showInstance(fragmentManager, result.getFilenames());
								}
							});
					View view = snackbar.getView();
					CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) view.getLayoutParams();
					params.gravity = Gravity.TOP;
					AndroidUtils.setMargins(params, 0, AndroidUtils.getStatusBarHeight(context), 0, 0);
					view.setLayoutParams(params);
					UiUtilities.setupSnackbar(snackbar, nightMode);
					snackbar.show();
					if (callback != null) {
						callback.run();
					}
				}
			}
		};
	}

	@Nullable
	public MapActivity getMapActivity() {
		Activity activity = getActivity();
		if (activity instanceof MapActivity) {
			return (MapActivity) activity;
		}
		return null;
	}

	public void show() {
		Dialog dialog = getDialog();
		if (dialog != null) {
			dialog.show();
		}
	}

	public void show(boolean updateTrackIcon) {
		show();
		if (updateTrackIcon && buttonAppearance != null) {
			updateTrackIcon(buttonAppearance);
		}
	}

	public void hide() {
		Dialog dialog = getDialog();
		if (dialog != null) {
			dialog.hide();
		}
	}

	public enum ItemType {
		SHOW_TRACK(R.string.shared_string_show_on_map, null),
		APPEARANCE(null, null),
		SEARCHING_GPS(R.string.searching_gps, R.drawable.ic_action_gps_info),
		RECORDING(R.string.recording_default_name, R.drawable.ic_action_track_recordable),
		ON_PAUSE(R.string.on_pause, R.drawable.ic_pause),
		CLEAR_DATA(R.string.clear_recorded_data, R.drawable.ic_action_delete_dark),
		START_SEGMENT(R.string.gpx_start_new_segment, R.drawable.ic_action_new_segment),
		SAVE(R.string.shared_string_save, R.drawable.ic_action_save_to_file),
		PAUSE(R.string.shared_string_pause, R.drawable.ic_pause),
		RESUME(R.string.shared_string_resume, R.drawable.ic_play_dark),
		STOP(R.string.shared_string_control_stop, R.drawable.ic_action_rec_stop),
		STOP_AND_DISCARD(R.string.track_recording_stop_without_saving, R.drawable.ic_action_rec_stop),
		STOP_AND_SAVE(R.string.track_recording_save_and_stop, R.drawable.ic_action_save_to_file),
		STOP_ONLINE(R.string.live_monitoring_stop, R.drawable.ic_world_globe_dark),
		CANCEL(R.string.shared_string_cancel, R.drawable.ic_action_close);

		@StringRes
		private final Integer titleId;
		@DrawableRes
		private final Integer iconId;
		private static final List<ItemType> negative = Arrays.asList(CLEAR_DATA, STOP_AND_DISCARD);

		ItemType(@Nullable @StringRes Integer titleId, @Nullable @DrawableRes Integer iconId) {
			this.titleId = titleId;
			this.iconId = iconId;
		}

		@Nullable
		public Integer getTitleId() {
			return titleId;
		}

		@Nullable
		public Integer getIconId() {
			return iconId;
		}

		public boolean isNegative() {
			return negative.contains(this);
		}
	}

	public static void setItemBackground(Context context, boolean nightMode, LinearLayout view, boolean enabled) {
		Drawable background = AppCompatResources.getDrawable(context, R.drawable.btn_background_inactive_light);
		if (background != null && enabled) {
			ColorStateList iconColorStateList = AndroidUtils.createPressedColorStateList(
					context, getInactiveButtonColorId(nightMode), getActiveButtonColorId(nightMode)
			);
			DrawableCompat.setTintList(background, iconColorStateList);
		} else {
			UiUtilities.tintDrawable(background, ContextCompat.getColor(context, getInactiveButtonColorId(nightMode)));
		}
		view.setBackgroundDrawable(background);
	}

	public static void setShowOnMapBackground(LinearLayout view, Context context, boolean checked, boolean nightMode) {
		Drawable background = AppCompatResources.getDrawable(context,
				nightMode ? checked ? R.drawable.btn_background_inactive_dark : R.drawable.btn_background_stroked_inactive_dark
						: checked ? R.drawable.btn_background_inactive_light : R.drawable.btn_background_stroked_inactive_light);
		view.setBackgroundDrawable(background);
	}

	public static void setTextColor(Context context, TextView tv, boolean enabled, boolean nightMode, ItemType type) {
		if (tv != null) {
			int activeColorId = type.isNegative() ? R.color.color_osm_edit_delete : getActiveTextColorId(nightMode);
			int normalColorId = enabled ? activeColorId : getSecondaryTextColorId(nightMode);
			ColorStateList textColorStateList = AndroidUtils.createPressedColorStateList(context, normalColorId, getPressedColorId(nightMode));
			tv.setTextColor(textColorStateList);
		}
	}

	public static void setTintedIcon(Context context, AppCompatImageView iv, boolean enabled, boolean nightMode, ItemType type) {
		Integer iconId = type.getIconId();
		if (iv != null && iconId != null) {
			Drawable icon = AppCompatResources.getDrawable(context, iconId);
			int activeColorId = type.isNegative() ? R.color.color_osm_edit_delete : getActiveIconColorId(nightMode);
			int normalColorId = enabled ? activeColorId : getSecondaryIconColorId(nightMode);
			ColorStateList iconColorStateList = AndroidUtils.createPressedColorStateList(context, normalColorId, getPressedColorId(nightMode));
			if (icon != null) {
				DrawableCompat.setTintList(icon, iconColorStateList);
			}
			iv.setImageDrawable(icon);
			if (type.iconId == R.drawable.ic_action_rec_stop) {
				int stopSize = iv.getResources().getDimensionPixelSize(R.dimen.bottom_sheet_icon_margin_large);
				LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(stopSize, stopSize);
				iv.setLayoutParams(params);
			}
		}
	}

	@ColorRes
	public static int getActiveTextColorId(boolean nightMode) {
		return nightMode ? R.color.active_color_primary_dark : R.color.active_color_primary_light;
	}

	@ColorRes
	public static int getSecondaryTextColorId(boolean nightMode) {
		return nightMode ? R.color.text_color_secondary_dark : R.color.text_color_secondary_light;
	}

	@ColorRes
	public static int getActiveIconColorId(boolean nightMode) {
		return nightMode ? R.color.icon_color_active_dark : R.color.icon_color_active_light;
	}

	@ColorRes
	public static int getSecondaryIconColorId(boolean nightMode) {
		return nightMode ? R.color.icon_color_secondary_dark : R.color.icon_color_secondary_light;
	}

	@ColorRes
	public static int getActiveButtonColorId(boolean nightMode) {
		return nightMode ? R.color.active_buttons_and_links_bg_pressed_dark : R.color.active_buttons_and_links_bg_pressed_light;
	}

	@ColorRes
	public static int getInactiveButtonColorId(boolean nightMode) {
		return nightMode ? R.color.inactive_buttons_and_links_bg_dark : R.color.inactive_buttons_and_links_bg_light;
	}

	@ColorRes
	public static int getOsmandIconColorId(boolean nightMode) {
		return nightMode ? R.color.icon_color_osmand_dark : R.color.icon_color_osmand_light;
	}

	@ColorRes
	public static int getPressedColorId(boolean nightMode) {
		return nightMode ? R.color.active_buttons_and_links_text_dark : R.color.active_buttons_and_links_text_light;
	}

	@Override
	protected int getDismissButtonHeight() {
		return getResources().getDimensionPixelSize(R.dimen.bottom_sheet_cancel_button_height);
	}

	@Override
	protected int getDismissButtonTextId() {
		return R.string.shared_string_close;
	}

	@Override
	protected boolean useVerticalButtons() {
		return true;
	}
}
