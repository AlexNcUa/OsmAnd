package net.osmand.plus.onlinerouting.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.osmand.AndroidUtils;
import net.osmand.CallbackWithObject;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.UiUtilities;
import net.osmand.plus.UiUtilities.DialogButtonType;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.BaseOsmAndFragment;
import net.osmand.plus.mapcontextmenu.other.HorizontalSelectionAdapter.HorizontalSelectionItem;
import net.osmand.plus.onlinerouting.EngineParameter;
import net.osmand.plus.onlinerouting.OnlineRoutingFactory;
import net.osmand.plus.onlinerouting.OnlineRoutingHelper;
import net.osmand.plus.onlinerouting.VehicleType;
import net.osmand.plus.onlinerouting.ui.OnlineRoutingCard.OnTextChangedListener;
import net.osmand.plus.onlinerouting.engine.EngineType;
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine;
import net.osmand.plus.routepreparationmenu.cards.BaseCard;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.util.Algorithms;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine.CUSTOM_VEHICLE;

public class OnlineRoutingEngineFragment extends BaseOsmAndFragment {

	public static final String TAG = OnlineRoutingEngineFragment.class.getSimpleName();

	private static final String ENGINE_TYPE_KEY = "engine_type";
	private static final String ENGINE_CUSTOM_VEHICLE_KEY = "engine_custom_vehicle";
	private static final String EXAMPLE_LOCATION_KEY = "example_location";
	private static final String APP_MODE_KEY = "app_mode";
	private static final String EDITED_ENGINE_KEY = "edited_engine_key";

	private OsmandApplication app;
	private ApplicationMode appMode;
	private MapActivity mapActivity;
	private OnlineRoutingHelper helper;

	private View view;
	private ViewGroup segmentsContainer;
	private OnlineRoutingCard nameCard;
	private OnlineRoutingCard typeCard;
	private OnlineRoutingCard vehicleCard;
	private OnlineRoutingCard apiKeyCard;
	private OnlineRoutingCard exampleCard;
	private View testResultsContainer;
	private View saveButton;
	private ScrollView scrollView;
	private OnGlobalLayoutListener onGlobalLayout;
	private boolean isKeyboardShown = false;

	private OnlineRoutingEngine engine;
	private OnlineRoutingEngine initEngine;
	private String customVehicleKey;
	private ExampleLocation selectedLocation;
	private String editedEngineKey;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		app = requireMyApplication();
		mapActivity = getMapActivity();
		helper = app.getOnlineRoutingHelper();
		if (savedInstanceState != null) {
			restoreState(savedInstanceState);
		} else {
			initState();
		}
		requireMyActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			public void handleOnBackPressed() {
				MapActivity mapActivity = getMapActivity();
				if (mapActivity != null) {
					showExitDialog();
				}
			}
		});
	}

	@SuppressLint("ClickableViewAccessibility")
	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
							 @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		view = getInflater().inflate(
				R.layout.online_routing_engine_fragment, container, false);
		segmentsContainer = (ViewGroup) view.findViewById(R.id.segments_container);
		scrollView = (ScrollView) segmentsContainer.getParent();
		if (Build.VERSION.SDK_INT >= 21) {
			AndroidUtils.addStatusBarPadding21v(getContext(), view);
		}
		setupToolbar((Toolbar) view.findViewById(R.id.toolbar));

		setupNameCard();
		setupTypeCard();
		setupVehicleCard();
		setupApiKeyCard();
		setupExampleCard();
		setupResultsContainer();
		setupButtons();

		generateUniqueNameIfNeeded();
		updateCardViews(nameCard, typeCard, vehicleCard, exampleCard);

		scrollView.setOnTouchListener(new View.OnTouchListener() {
			int scrollViewY = 0;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				int y = scrollView.getScrollY();
				if (isKeyboardShown && scrollViewY != y) {
					scrollViewY = y;
					View focus = mapActivity.getCurrentFocus();
					if (focus != null) {
						AndroidUtils.hideSoftKeyboard(mapActivity, focus);
						focus.clearFocus();
					}
				}
				return false;
			}
		});

		onGlobalLayout = new ViewTreeObserver.OnGlobalLayoutListener() {
			private int layoutHeightPrevious;
			private int layoutHeightMin;

			@Override
			public void onGlobalLayout() {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
					view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
				} else {
					view.getViewTreeObserver().removeGlobalOnLayoutListener(this);
				}

				Rect visibleDisplayFrame = new Rect();
				view.getWindowVisibleDisplayFrame(visibleDisplayFrame);
				int layoutHeight = visibleDisplayFrame.bottom;

				if (layoutHeight < layoutHeightPrevious) {
					isKeyboardShown = true;
					layoutHeightMin = layoutHeight;
				} else {
					isKeyboardShown = layoutHeight == layoutHeightMin;
				}

				if (layoutHeight != layoutHeightPrevious) {
					FrameLayout.LayoutParams rootViewLayout = (FrameLayout.LayoutParams) view.getLayoutParams();
					rootViewLayout.height = layoutHeight;
					view.requestLayout();
					layoutHeightPrevious = layoutHeight;
				}

				view.post(new Runnable() {
					@Override
					public void run() {
						view.getViewTreeObserver().addOnGlobalLayoutListener(onGlobalLayout);
					}
				});

			}
		};

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			view.getViewTreeObserver().addOnGlobalLayoutListener(onGlobalLayout);
		}

		return view;
	}

	private void setupToolbar(Toolbar toolbar) {
		ImageView navigationIcon = toolbar.findViewById(R.id.close_button);
		navigationIcon.setImageResource(R.drawable.ic_action_close);
		navigationIcon.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				showExitDialog();
			}
		});
		TextView title = toolbar.findViewById(R.id.toolbar_title);
		toolbar.findViewById(R.id.toolbar_subtitle).setVisibility(View.GONE);
		View actionBtn = toolbar.findViewById(R.id.action_button);
		if (isEditingMode()) {
			title.setText(getString(R.string.edit_online_routing_engine));
			ImageView ivBtn = toolbar.findViewById(R.id.action_button_icon);
			ivBtn.setImageDrawable(
					getIcon(R.drawable.ic_action_delete_dark, R.color.color_osm_edit_delete));
			actionBtn.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					onDeleteEngine();
					dismiss();
				}
			});
		} else {
			title.setText(getString(R.string.add_online_routing_engine));
			actionBtn.setVisibility(View.GONE);
		}
	}

	private void setupNameCard() {
		nameCard = new OnlineRoutingCard(mapActivity, isNightMode(), appMode);
		nameCard.build(mapActivity);
		nameCard.setDescription(getString(R.string.select_nav_profile_dialog_message));
		nameCard.setEditedText(engine.getName(app));
		nameCard.setFieldBoxLabelText(getString(R.string.shared_string_name));
		nameCard.setOnTextChangedListener(new OnTextChangedListener() {
			@Override
			public void onTextChanged(boolean changedByUser, @NonNull String text) {
				if (changedByUser) {
					engine.put(EngineParameter.CUSTOM_NAME, text);
					checkCustomNameUnique(engine);
				}
			}
		});
		nameCard.showDivider();
		segmentsContainer.addView(nameCard.getView());
	}

	private void setupTypeCard() {
		typeCard = new OnlineRoutingCard(mapActivity, isNightMode(), appMode);
		typeCard.build(mapActivity);
		typeCard.setHeaderTitle(getString(R.string.shared_string_type));
		List<HorizontalSelectionItem> serverItems = new ArrayList<>();
		for (EngineType server : EngineType.values()) {
			serverItems.add(new HorizontalSelectionItem(server.getTitle(), server));
		}
		typeCard.setSelectionMenu(serverItems, engine.getType().getTitle(),
				new CallbackWithObject<HorizontalSelectionItem>() {
					@Override
					public boolean processResult(HorizontalSelectionItem result) {
						EngineType type = (EngineType) result.getObject();
						if (engine.getType() != type) {
							changeEngineType(type);
							return true;
						}
						return false;
					}
				});
		typeCard.setOnTextChangedListener(new OnTextChangedListener() {
			@Override
			public void onTextChanged(boolean editedByUser, @NonNull String text) {
				if (editedByUser) {
					engine.put(EngineParameter.CUSTOM_URL, text);
					updateCardViews(exampleCard);
				}
			}
		});
		typeCard.setFieldBoxLabelText(getString(R.string.shared_string_server_url));
		typeCard.showDivider();
		segmentsContainer.addView(typeCard.getView());
	}

	private void setupVehicleCard() {
		vehicleCard = new OnlineRoutingCard(mapActivity, isNightMode(), appMode);
		vehicleCard.build(mapActivity);
		vehicleCard.setHeaderTitle(getString(R.string.shared_string_vehicle));
		vehicleCard.setFieldBoxLabelText(getString(R.string.shared_string_custom));
		vehicleCard.setOnTextChangedListener(new OnTextChangedListener() {
			@Override
			public void onTextChanged(boolean editedByUser, @NonNull String text) {
				if (editedByUser) {
					customVehicleKey = text;
					engine.put(EngineParameter.VEHICLE_KEY, customVehicleKey);
					updateCardViews(nameCard, exampleCard);
				}
			}
		});
		vehicleCard.setEditedText(customVehicleKey);
		vehicleCard.setFieldBoxHelperText(getString(R.string.shared_string_enter_param));
		vehicleCard.showDivider();
		segmentsContainer.addView(vehicleCard.getView());
		setupVehicleTypes();
	}

	private void setupVehicleTypes() {
		List<HorizontalSelectionItem> vehicleItems = new ArrayList<>();
		for (VehicleType vehicle : engine.getAllowedVehicles()) {
			vehicleItems.add(new HorizontalSelectionItem(vehicle.getTitle(app), vehicle));
		}
		vehicleCard.setSelectionMenu(vehicleItems, engine.getSelectedVehicleType().getTitle(app),
				new CallbackWithObject<HorizontalSelectionItem>() {
					@Override
					public boolean processResult(HorizontalSelectionItem result) {
						VehicleType vehicle = (VehicleType) result.getObject();
						if (!Algorithms.objectEquals(engine.getSelectedVehicleType(), vehicle)) {
							String vehicleKey = vehicle.equals(CUSTOM_VEHICLE) ? customVehicleKey : vehicle.getKey();
							engine.put(EngineParameter.VEHICLE_KEY, vehicleKey);
							generateUniqueNameIfNeeded();
							updateCardViews(nameCard, vehicleCard, exampleCard);
							return true;
						}
						return false;
					}
				});
	}

	private void setupApiKeyCard() {
		apiKeyCard = new OnlineRoutingCard(mapActivity, isNightMode(), appMode);
		apiKeyCard.build(mapActivity);
		apiKeyCard.setHeaderTitle(getString(R.string.shared_string_api_key));
		apiKeyCard.setFieldBoxLabelText(getString(R.string.keep_it_empty_if_not));
		String apiKey = engine.get(EngineParameter.API_KEY);
		if (apiKey != null) {
			apiKeyCard.setEditedText(apiKey);
		}
		apiKeyCard.showDivider();
		apiKeyCard.setOnTextChangedListener(new OnTextChangedListener() {
			@Override
			public void onTextChanged(boolean editedByUser, @NonNull String text) {
				if (Algorithms.isBlank(text)) {
					engine.remove(EngineParameter.API_KEY);
				} else {
					engine.put(EngineParameter.API_KEY, text);
				}
				updateCardViews(exampleCard);
			}
		});
		segmentsContainer.addView(apiKeyCard.getView());
	}

	private void setupExampleCard() {
		exampleCard = new OnlineRoutingCard(mapActivity, isNightMode(), appMode);
		exampleCard.build(mapActivity);
		exampleCard.setHeaderTitle(getString(R.string.shared_string_example));
		List<HorizontalSelectionItem> locationItems = new ArrayList<>();
		for (ExampleLocation location : ExampleLocation.values()) {
			locationItems.add(new HorizontalSelectionItem(location.getName(), location));
		}
		exampleCard.setSelectionMenu(locationItems, selectedLocation.getName(),
				new CallbackWithObject<HorizontalSelectionItem>() {
					@Override
					public boolean processResult(HorizontalSelectionItem result) {
						ExampleLocation location = (ExampleLocation) result.getObject();
						if (selectedLocation != location) {
							selectedLocation = location;
							updateCardViews(exampleCard);
							return true;
						}
						return false;
					}
				});
		exampleCard.setDescription(getString(R.string.online_routing_example_hint));
		exampleCard.showFieldBox();
		exampleCard.setButton(getString(R.string.test_route_calculation), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				testEngineWork();
			}
		});
		segmentsContainer.addView(exampleCard.getView());
	}

	private void setupResultsContainer() {
		testResultsContainer = getInflater().inflate(
				R.layout.bottom_sheet_item_with_descr_64dp, segmentsContainer, false);
		testResultsContainer.setVisibility(View.INVISIBLE);
		segmentsContainer.addView(testResultsContainer);
	}

	private void setupButtons() {
		boolean nightMode = isNightMode();
		View cancelButton = view.findViewById(R.id.dismiss_button);
		UiUtilities.setupDialogButton(nightMode, cancelButton,
				DialogButtonType.SECONDARY, R.string.shared_string_cancel);
		cancelButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showExitDialog();
			}
		});

		view.findViewById(R.id.buttons_divider).setVisibility(View.VISIBLE);

		saveButton = view.findViewById(R.id.right_bottom_button);
		UiUtilities.setupDialogButton(nightMode, saveButton,
				UiUtilities.DialogButtonType.PRIMARY, R.string.shared_string_save);
		saveButton.setVisibility(View.VISIBLE);
		saveButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				onSaveEngine();
				dismiss();
			}
		});
	}

	private void changeEngineType(EngineType type) {
		OnlineRoutingEngine tmp = (OnlineRoutingEngine) engine.clone();
		engine = OnlineRoutingFactory.createEngine(type, tmp.getParams());

		// after changing the type, select the vehicle
		// with the same name that was selected before
		VehicleType previous = tmp.getSelectedVehicleType();
		VehicleType next = null;
		for (VehicleType vt : engine.getAllowedVehicles()) {
			if (Algorithms.objectEquals(previous.getTitle(app), vt.getTitle(app))) {
				next = vt;
				break;
			}
		}
		String vehicleKey;
		if (next != null) {
			vehicleKey = next.equals(CUSTOM_VEHICLE) ? customVehicleKey : next.getKey();
		} else {
			vehicleKey = engine.getAllowedVehicles().get(0).getKey();
		}
		engine.put(EngineParameter.VEHICLE_KEY, vehicleKey);

		setupVehicleTypes();
		generateUniqueNameIfNeeded();
		updateCardViews(nameCard, typeCard, vehicleCard, exampleCard);
	}

	private void generateUniqueNameIfNeeded() {
		if (engine.get(EngineParameter.CUSTOM_NAME) == null) {
			engine.remove(EngineParameter.NAME_INDEX);
			if (hasNameDuplicate(engine.getName(app))) {
				int index = 0;
				do {
					engine.put(EngineParameter.NAME_INDEX, String.valueOf(++index));
				} while (hasNameDuplicate(engine.getName(app)));
			}
		}
	}

	private void checkCustomNameUnique(@NonNull OnlineRoutingEngine engine) {
		if (hasNameDuplicate(engine.getName(app))) {
			nameCard.showFieldBoxError(getString(R.string.message_name_is_already_exists));
			saveButton.setEnabled(false);
		} else {
			nameCard.hideFieldBoxError();
			saveButton.setEnabled(true);
		}
	}

	private boolean hasNameDuplicate(@NonNull String name) {
		for (OnlineRoutingEngine engine : helper.getEnginesExceptMentioned(editedEngineKey)) {
			if (Algorithms.objectEquals(engine.getName(app), name)) {
				return true;
			}
		}
		return false;
	}

	private void onSaveEngine() {
		if (engine != null) {
			helper.saveEngine(engine);
		}
	}

	private void onDeleteEngine() {
		helper.deleteEngine(engine);
	}

	private boolean isEditingMode() {
		return editedEngineKey != null;
	}

	private String getTestUrl() {
		List<LatLon> path = new ArrayList<>();
		path.add(selectedLocation.getCityCenterLatLon());
		path.add(selectedLocation.getCityAirportLatLon());
		return engine.getFullUrl(path);
	}

	private void testEngineWork() {
		final OnlineRoutingEngine requestedEngine = (OnlineRoutingEngine) engine.clone();
		final ExampleLocation location = selectedLocation;
		new Thread(new Runnable() {
			@Override
			public void run() {
				StringBuilder message = new StringBuilder();
				boolean resultOk = false;
				try {
					String response = helper.makeRequest(exampleCard.getEditedText());
					resultOk = requestedEngine.parseServerMessage(message, response);
				} catch (IOException | JSONException e) {
					message.append(e.toString());
				}
				showTestResults(resultOk, message.toString(), location);
			}
		}).start();
	}

	private void showTestResults(final boolean resultOk,
	                             final @NonNull String message,
	                             final @NonNull ExampleLocation location) {
		app.runInUIThread(new Runnable() {
			@Override
			public void run() {
				testResultsContainer.setVisibility(View.VISIBLE);
				ImageView ivImage = testResultsContainer.findViewById(R.id.icon);
				TextView tvTitle = testResultsContainer.findViewById(R.id.title);
				TextView tvDescription = testResultsContainer.findViewById(R.id.description);
				if (resultOk) {
					ivImage.setImageDrawable(getContentIcon(R.drawable.ic_action_gdirections_dark));
					tvTitle.setText(getString(R.string.shared_string_ok));
				} else {
					ivImage.setImageDrawable(getContentIcon(R.drawable.ic_action_alert));
					tvTitle.setText(String.format(getString(R.string.message_server_error), message));
				}
				tvDescription.setText(location.getName());
			}
		});
	}

	private void updateCardViews(@NonNull BaseCard... cardsToUpdate) {
		for (BaseCard card : cardsToUpdate) {
			if (nameCard.equals(card)) {
				if (Algorithms.isEmpty(engine.get(EngineParameter.CUSTOM_NAME))) {
					nameCard.setEditedText(engine.getName(app));
				}

			} else if (typeCard.equals(card)) {
				typeCard.setHeaderSubtitle(engine.getType().getTitle());
				typeCard.setEditedText(engine.getBaseUrl());
				if (engine.isParameterAllowed(EngineParameter.API_KEY)) {
					apiKeyCard.show();
				} else {
					apiKeyCard.hide();
				}

			} else if (vehicleCard.equals(card)) {
				VehicleType vt = engine.getSelectedVehicleType();
				vehicleCard.setHeaderSubtitle(vt.getTitle(app));
				if (vt.equals(CUSTOM_VEHICLE)) {
					vehicleCard.showFieldBox();
					vehicleCard.setEditedText(customVehicleKey);
				} else {
					vehicleCard.hideFieldBox();
				}

			} else if (exampleCard.equals(card)) {
				exampleCard.setEditedText(getTestUrl());
			}
		}
	}

	public void showExitDialog() {
		View focus = view.findFocus();
		AndroidUtils.hideSoftKeyboard(mapActivity, focus);
		if (!engine.equals(initEngine)) {
			AlertDialog.Builder dismissDialog = createWarningDialog(mapActivity,
					R.string.shared_string_dismiss, R.string.exit_without_saving, R.string.shared_string_cancel);
			dismissDialog.setPositiveButton(R.string.shared_string_exit, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					dismiss();
				}
			});
			dismissDialog.show();
		} else {
			dismiss();
		}
	}

	private AlertDialog.Builder createWarningDialog(Activity activity, int title, int message, int negButton) {
		Context themedContext = UiUtilities.getThemedContext(activity, isNightMode());
		AlertDialog.Builder warningDialog = new AlertDialog.Builder(themedContext);
		warningDialog.setTitle(getString(title));
		warningDialog.setMessage(getString(message));
		warningDialog.setNegativeButton(negButton, null);
		return warningDialog;
	}

	private void dismiss() {
		FragmentActivity activity = getActivity();
		if (activity != null) {
			FragmentManager fragmentManager = activity.getSupportFragmentManager();
			if (!fragmentManager.isStateSaved()) {
				fragmentManager.popBackStack();
			}
		}
	}

	private boolean isNightMode() {
		return !app.getSettings().isLightContentForMode(getAppMode());
	}

	@NonNull
	private ApplicationMode getAppMode() {
		return appMode != null ? appMode : app.getSettings().getApplicationMode();
	}

	@Nullable
	private MapActivity getMapActivity() {
		FragmentActivity activity = getActivity();
		if (activity instanceof MapActivity) {
			return (MapActivity) activity;
		} else {
			return null;
		}
	}

	private LayoutInflater getInflater() {
		return UiUtilities.getInflater(mapActivity, isNightMode());
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			view.getViewTreeObserver().removeOnGlobalLayoutListener(onGlobalLayout);
		} else {
			view.getViewTreeObserver().removeGlobalOnLayoutListener(onGlobalLayout);
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		saveState(outState);
	}

	private void saveState(@NonNull Bundle outState) {
		outState.putString(ENGINE_TYPE_KEY, engine.getType().name());
		for (EngineParameter key : EngineParameter.values()) {
			String value = engine.get(key);
			if (value != null) {
				outState.putString(key.name(), value);
			}
		}
		outState.putString(ENGINE_CUSTOM_VEHICLE_KEY, customVehicleKey);
		outState.putString(EXAMPLE_LOCATION_KEY, selectedLocation.name());
		outState.putString(APP_MODE_KEY, getAppMode().getStringKey());
		outState.putString(EDITED_ENGINE_KEY, editedEngineKey);
	}

	private void restoreState(@NonNull Bundle savedState) {
		editedEngineKey = savedState.getString(EngineParameter.KEY.name());
		initEngine = createInitStateEngine();
		String typeKey = savedState.getString(ENGINE_TYPE_KEY);
		EngineType type = EngineType.getTypeByName(typeKey);
		engine = OnlineRoutingFactory.createEngine(type);
		for (EngineParameter key : EngineParameter.values()) {
			String value = savedState.getString(key.name());
			if (value != null) {
				engine.put(key, value);
			}
		}
		customVehicleKey = savedState.getString(ENGINE_CUSTOM_VEHICLE_KEY);
		selectedLocation = ExampleLocation.valueOf(savedState.getString(EXAMPLE_LOCATION_KEY));
		appMode = ApplicationMode.valueOfStringKey(savedState.getString(APP_MODE_KEY), null);
	}

	private void initState() {
		initEngine = createInitStateEngine();
		selectedLocation = ExampleLocation.values()[0];
		engine = (OnlineRoutingEngine) initEngine.clone();
		if (Algorithms.objectEquals(engine.getSelectedVehicleType(), CUSTOM_VEHICLE)) {
			customVehicleKey = engine.get(EngineParameter.VEHICLE_KEY);
		} else {
			customVehicleKey = "";
		}
	}

	private OnlineRoutingEngine createInitStateEngine() {
		OnlineRoutingEngine engine;
		OnlineRoutingEngine editedEngine = helper.getEngineByKey(editedEngineKey);
		if (editedEngine != null) {
			engine = (OnlineRoutingEngine) editedEngine.clone();
		} else {
			engine = OnlineRoutingFactory.createEngine(EngineType.values()[0]);
			String vehicle = engine.getAllowedVehicles().get(0).getKey();
			engine.put(EngineParameter.VEHICLE_KEY, vehicle);
			if (editedEngineKey != null) {
				engine.put(EngineParameter.KEY, editedEngineKey);
			}
		}
		return engine;
	}

	public static void showInstance(@NonNull FragmentActivity activity,
	                                @NonNull ApplicationMode appMode,
	                                @Nullable String editedEngineKey) {
		FragmentManager fm = activity.getSupportFragmentManager();
		if (!fm.isStateSaved() && fm.findFragmentByTag(OnlineRoutingEngineFragment.TAG) == null) {
			OnlineRoutingEngineFragment fragment = new OnlineRoutingEngineFragment();
			fragment.appMode = appMode;
			fragment.editedEngineKey = editedEngineKey;
			fm.beginTransaction()
					.add(R.id.fragmentContainer, fragment, TAG)
					.addToBackStack(TAG).commitAllowingStateLoss();
		}
	}
}
