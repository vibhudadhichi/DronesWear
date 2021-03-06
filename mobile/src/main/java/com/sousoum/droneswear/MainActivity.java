package com.sousoum.droneswear;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.parrot.arsdk.ARSDK;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DEVICE_STATE_ENUM;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;
import com.parrot.arsdk.arsal.ARSALPrint;
import com.parrot.arsdk.arsal.ARSAL_PRINT_LEVEL_ENUM;
import com.sousoum.discovery.Discoverer;
import com.sousoum.drone.ParrotDrone;
import com.sousoum.drone.ParrotDroneFactory;
import com.sousoum.drone.ParrotFlyingDrone;
import com.sousoum.drone.ParrotJumpingDrone;
import com.sousoum.shared.AccelerometerData;
import com.sousoum.shared.ActionType;
import com.sousoum.shared.InteractionType;
import com.sousoum.shared.JoystickData;
import com.sousoum.shared.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks, DataApi.DataListener, Discoverer.DiscovererListener, ParrotDrone.ParrotDroneListener {
    private static final String TAG = "MobileMainActivity";

    private static final int ALPHA_ANIM_DURATION = 500;

    /** Code for permission request result handling. */
    private static final int REQUEST_CODE_PERMISSIONS_REQUEST = 1;

    private GoogleApiClient mGoogleApiClient;

    private final Object mDroneLock = new Object();

    private ParrotDrone mDrone;

    private View mTimeoutHelper;
    private Button mEmergencyBt;
    private TextView mConnectionTextView;
    private TextView mWifiTextView;
    private TextView mPilotingTextView;
    private Switch mAcceleroSwitch;

    private Handler mHandler;
    private Runnable mAnimRunnable;
    private Runnable mReconnectRunnable;

    static {
        ARSDK.loadSDKLibs();
        ARSALPrint.setMinimumLogLevel(ARSAL_PRINT_LEVEL_ENUM.ARSAL_PRINT_VERBOSE);
    }

    private boolean mUseWatchAccelero;
    private Discoverer mDiscoverer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTimeoutHelper = findViewById(R.id.timeout_helper);
        mConnectionTextView = (TextView) findViewById(R.id.connection_text_view);
        mWifiTextView = (TextView) findViewById(R.id.wifi_text_view);
        mPilotingTextView = (TextView) findViewById(R.id.piloting_text_view);

        mAcceleroSwitch = (Switch) findViewById(R.id.acceleroSwitch);
        mAcceleroSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                onAcceleroSwitchCheckChanged();
            }
        });
        mEmergencyBt = (Button) findViewById(R.id.emergencyBt);
        mEmergencyBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onEmergencyClicked();
            }
        });

        mUseWatchAccelero = false;
        mAcceleroSwitch.setChecked(mUseWatchAccelero);
        updatePilotingText();

        mGoogleApiClient = new GoogleApiClient.Builder(this).addConnectionCallbacks(this).addApi(Wearable.API).build();


        mDiscoverer = new Discoverer(this);
        mDiscoverer.addListener(this);

        mHandler = new Handler(getMainLooper());
        mAnimRunnable = new Runnable() {
            @Override
            public void run() {
                startBounceAnimation();
            }
        };

        mReconnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (mDiscoverer != null) {
                    mDiscoverer.startDiscovering();
                    mConnectionTextView.setText(R.string.discovering);
                }
            }
        };

        Set<String> permissionsToRequest = new HashSet<>();
        String permission = Manifest.permission.ACCESS_COARSE_LOCATION;
        if (ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                Toast.makeText(this, "Please allow permission " + permission, Toast.LENGTH_LONG).show();
                finish();
                return;
            } else {
                permissionsToRequest.add(permission);
            }
        }
        if (permissionsToRequest.size() > 0) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[permissionsToRequest.size()]),
                    REQUEST_CODE_PERMISSIONS_REQUEST);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        Message.sendInteractionTypeMessage(InteractionType.NONE, mGoogleApiClient);
        PendingResult<DataApi.DataItemResult> pendingResult = Message.sendActionTypeMessage(ActionType.NONE, mGoogleApiClient);
        pendingResult.setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
            @Override
            public void onResult(DataApi.DataItemResult dataItemsResult) {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, MainActivity.this);
                    mGoogleApiClient.disconnect();
                }
            }
        });

        mDiscoverer.cleanup();
    }

    @Override
    protected void onResume() {
        super.onResume();

        mGoogleApiClient.connect();

        mDiscoverer.setup();
        mConnectionTextView.setText(R.string.discovering);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean denied = false;
        if (permissions.length == 0) {
            // canceled, finish
            denied = true;
        } else {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    denied = true;
                }
            }
        }

        if (denied) {
            Toast.makeText(this, "At least one permission is missing.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void sendActionType(int actionType) {
        Message.sendActionTypeMessage(actionType, mGoogleApiClient);
    }

    private void sendInteractionType() {
        int interactionType = InteractionType.NONE;
        if (mDrone != null) {
            if (mDrone instanceof ParrotFlyingDrone) {
                interactionType = InteractionType.ACTION | InteractionType.JOYSTICK;
            } else if (mDrone instanceof ParrotJumpingDrone) {
                interactionType = InteractionType.ACTION;
            }
        }

        Message.sendInteractionTypeMessage(interactionType, mGoogleApiClient);
    }

    //region ParrotDroneListener
    @Override
    public void onDroneConnectionChanged(ARCONTROLLER_DEVICE_STATE_ENUM state) {
        switch (state) {
            case ARCONTROLLER_DEVICE_STATE_RUNNING:
                mDiscoverer.stopDiscovering();
                mConnectionTextView.setText(String.format(getString(R.string.device_connected), mDrone.getName()));
                mWifiTextView.setVisibility(View.VISIBLE);
                mPilotingTextView.setVisibility(View.VISIBLE);
                sendInteractionType();
                break;
            case ARCONTROLLER_DEVICE_STATE_STOPPED:
                synchronized (mDroneLock) {
                    mDrone = null;
                }
                mConnectionTextView.setText(R.string.device_disconnected);
                mWifiTextView.setVisibility(View.GONE);
                mPilotingTextView.setVisibility(View.GONE);
                sendInteractionType();
                sendActionType(ActionType.NONE);
                mHandler.postDelayed(mReconnectRunnable, 5000);
                break;
        }
    }

    @Override
    public void onDroneActionChanged(int action) {
        switch (action) {
            case ActionType.LAND:
                mEmergencyBt.setVisibility(View.VISIBLE);
                break;
            default:
                mEmergencyBt.setVisibility(View.GONE);
                break;
        }

        sendActionType(action);
    }

    @Override
    public void onDroneWifiBandChanged(int band) {
        switch (band) {
            case ParrotDrone.WIFI_BAND_2_4GHZ:
                mWifiTextView.setText(R.string.wifi_band_2ghz);
                break;
            case ParrotDrone.WIFI_BAND_5GHZ:
                mWifiTextView.setText(null);
                break;
        }
    }
    //endregion ParrotDroneListener

    //region DiscovererListener
    @Override
    public void onServiceDiscovered(ARDiscoveryDeviceService deviceService) {
        mTimeoutHelper.setVisibility(View.GONE);
        mConnectionTextView.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mAnimRunnable);
        if (mDrone == null) {
            mConnectionTextView.setText(String.format(getString(R.string.connecting_to_device), deviceService.getName()));
            mDiscoverer.stopDiscovering();
            synchronized (mDroneLock) {
                mDrone = ParrotDroneFactory.createParrotDrone(deviceService, this);
                mDrone.addListener(this);
            }
        }
    }

    @Override
    public void onDiscoveryTimedOut() {
        mTimeoutHelper.setVisibility(View.VISIBLE);
        mConnectionTextView.setVisibility(View.GONE);
        startAnimation();
    }
    //endregion DiscovererListener

    //region Animations
    private void startAnimation() {
        ArrayList<View> layoutArray = new ArrayList<>();
        layoutArray.add(findViewById(R.id.timeout_helper_1));
        layoutArray.add(findViewById(R.id.timeout_helper_2));
        layoutArray.add(findViewById(R.id.timeout_helper_3));
        layoutArray.add(findViewById(R.id.timeout_helper_4));
        layoutArray.add(findViewById(R.id.timeout_helper_5));
        layoutArray.add(findViewById(R.id.timeout_helper_6));

        ObjectAnimator animation;

        View timeoutTitle = findViewById(R.id.timeout_title);
        animation = ObjectAnimator.ofFloat(timeoutTitle, View.ALPHA, 0, 1);
        animation.setDuration(ALPHA_ANIM_DURATION);
        animation.start();

        for (View view : layoutArray) {
            animation = ObjectAnimator.ofFloat(view, View.ALPHA, 0, 1);
            animation.setDuration(ALPHA_ANIM_DURATION);
            animation.start();
        }

        startBounceAnimation();
    }

    private void startBounceAnimation() {
        ArrayList<View> layoutArray = new ArrayList<>();
        layoutArray.add(findViewById(R.id.timeout_helper_1));
        layoutArray.add(findViewById(R.id.timeout_helper_2));
        layoutArray.add(findViewById(R.id.timeout_helper_3));
        layoutArray.add(findViewById(R.id.timeout_helper_4));
        layoutArray.add(findViewById(R.id.timeout_helper_5));
        layoutArray.add(findViewById(R.id.timeout_helper_6));

        ObjectAnimator animation;
        int translationX = 30;
        int animationDuration = 200;
        int animationDelay = 50;
        int layoutIdx = 0;

        for (View view : layoutArray) {
            animation = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, translationX);
            animation.setDuration(animationDuration);
            animation.setStartDelay((animationDelay * layoutIdx) + ALPHA_ANIM_DURATION);
            animation.start();

            layoutIdx++;
        }

        layoutIdx = 0;
        for (View view : layoutArray) {
            animation = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0);
            animation.setDuration(animationDuration);
            animation.setStartDelay((animationDelay * layoutIdx) + ALPHA_ANIM_DURATION + animationDuration);
            animation.start();

            layoutIdx++;
        }

        mHandler.postDelayed(mAnimRunnable, 10000);
    }
    //endregion Animations

    //region Button Listeners
    private void onEmergencyClicked() {
        if (mDrone != null && (mDrone instanceof ParrotFlyingDrone)) {
            ((ParrotFlyingDrone) mDrone).sendEmergency();
        }
    }

    private void onAcceleroSwitchCheckChanged() {
        mUseWatchAccelero = mAcceleroSwitch.isChecked();
        Log.i(TAG, "Accelero is checked = " + mUseWatchAccelero);
        if (!mUseWatchAccelero && mDrone != null) {
            mDrone.stopPiloting();
        }

        updatePilotingText();
    }

    private void updatePilotingText() {
        mPilotingTextView.setText((mUseWatchAccelero) ? R.string.with_piloting : R.string.no_piloting);
    }

    //endregion Button Listeners

    //region DataApi.DataListener
    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {

            DataItem dataItem = event.getDataItem();
            Message.MESSAGE_TYPE messageType = Message.getMessageType(dataItem);
            if (event.getType() == DataEvent.TYPE_DELETED) {
                switch (messageType) {
                    case ACC:
                        synchronized (mDroneLock) {
                            if (mDrone != null && mUseWatchAccelero) {
                                mDrone.stopPiloting();
                            }

                        }
                        break;
                    case JOYSTICK:
                        synchronized (mDroneLock) {
                            if (mDrone != null) {
                                mDrone.stopPiloting();
                            }

                        }
                        break;
                }
            } else if (event.getType() == DataEvent.TYPE_CHANGED) {
                switch (messageType) {
                    case ACC:
                        synchronized (mDroneLock) {
                            if (mDrone != null && mUseWatchAccelero) {
                                AccelerometerData accelerometerData = Message.decodeAcceleroMessage(dataItem);
                                if (accelerometerData != null) {
                                    mDrone.pilotWithAcceleroData(accelerometerData);
                                } else {
                                    mDrone.stopPiloting();
                                }
                            }

                        }
                        break;
                    case JOYSTICK:
                        synchronized (mDroneLock) {
                            if (mDrone != null) {
                                JoystickData joystickData = Message.decodeJoystickMessage(dataItem);
                                if (joystickData != null) {
                                    mDrone.pilotWithJoystickData(joystickData);
                                } else {
                                    mDrone.stopPiloting();
                                }
                            }

                        }
                        break;
                    case ACTION:
                        if (mDrone != null) {
                            mDrone.sendAction();
                        }
                        break;
                }

            }
        }
    }
    //endregion DataApi.DataListener

    //region GoogleApiClient.ConnectionCallbacks
    @Override
    public void onConnected(Bundle bundle) {
        Wearable.DataApi.addListener(mGoogleApiClient, this);

        if (mDrone != null) {
            Message.sendActionTypeMessage(mDrone.getCurrentAction(), mGoogleApiClient);
        }
        sendInteractionType();

        // launch the app on the wear
        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                for (Node node : getConnectedNodesResult.getNodes()) {
                    Wearable.MessageApi.sendMessage(mGoogleApiClient , node.getId() , Message.OPEN_ACTIVITY_MESSAGE , new byte[0]);
                }
            }
        });
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.i(TAG, "onConnectionSuspended");
    }
    //endregion GoogleApiClient.ConnectionCallbacks
}
