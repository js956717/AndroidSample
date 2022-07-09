package com.microsoft.azure.iot.sdk.samples.androidsample;


import static com.microsoft.azure.iot.sdk.samples.androidsample.GlobalData.*;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodData;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeCallback;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeReason;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubMessageResult;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private final String connString = BuildConfig.DeviceConnectionString;;
    private static final String TAG = "MainActivity";

    // accelerometer, gyroscope sensors
    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope;


    // textViews
    private TextView txt_lat, txt_long;

    private TextView txt_accelX, txt_accelY, txt_accelZ;
    private TextView txt_gyroX, txt_gyroY, txt_gyroZ;


    private double temperature;
    private double humidity;
    private Message sendMessage;
    private String lastException;

    private DeviceClient client;

    IotHubClientProtocol protocol = IotHubClientProtocol.MQTT;

    Button btnStart;
    Button btnStop;

    TextView txtMsgsSentVal;
    TextView txtLastAccelVal;
    TextView txtLastGyroscopeVal;
    TextView txtLastMsgSentVal;
    TextView txtLastMsgReceivedVal;

    private int msgSentCount = 0;
    private int receiptsConfirmedCount = 0;
    private int sendFailuresCount = 0;
    private int msgReceivedCount = 0;
    private int sendMessagesInterval = 5000;

    private final Handler handler = new Handler();
    private Thread sendThread;

    private static final int METHOD_SUCCESS = 200;
    public static final int METHOD_THROWS = 403;
    private static final int METHOD_NOT_DEFINED = 404;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();
        initSensors();

        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            startLocationService();
        }
    }

    void startLocationService() {
        LocationBroadcastReceiver receiver = new LocationBroadcastReceiver();
        IntentFilter filter = new IntentFilter("ACT_LOC");
        registerReceiver(receiver, filter);
        Intent intent = new Intent(MainActivity.this, LocationService.class);
        startService(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == 1) {
            if(grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationService();
            } else {
                Toast.makeText(this, "App requires location permissions", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public class LocationBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals("ACT_LOC")) {
                double latitude = intent.getDoubleExtra("latitude", 0f);
                double longitude = intent.getDoubleExtra("longitude", 0f);

                // update global variables
                gpsLat = latitude;
                gpsLong = longitude;

                txt_lat.setText(String.format("Latitude: %s", latitude));
                txt_long.setText(String.format("Latitude: %s", longitude));

                Log.d("loc_report", "lat: " + latitude + ", long: " + longitude);
            }
        }
    }

    private void initUI() {
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        txtMsgsSentVal = findViewById(R.id.txtMsgsSentVal);

        txtLastAccelVal = findViewById(R.id.txtLastAccelVal);
        txtLastGyroscopeVal = findViewById(R.id.txtLastGyroscopeVal);
        txtLastMsgSentVal = findViewById(R.id.txtLastMsgSentVal);
        txtLastMsgReceivedVal = findViewById(R.id.txtLastMsgReceivedVal);

        btnStop.setEnabled(false);

        txt_lat = findViewById(R.id.loc_lat);
        txt_long = findViewById(R.id.loc_long);

        txt_accelX = findViewById(R.id.accelX);
        txt_accelY = findViewById(R.id.accelY);
        txt_accelZ = findViewById(R.id.accelZ);

        txt_gyroX = findViewById(R.id.gyroX);
        txt_gyroY = findViewById(R.id.gyroY);
        txt_gyroZ = findViewById(R.id.gyroZ);
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if(accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "onCreate: Registered accelerometer listener");
        } else {
            Log.d(TAG, "Accelerometer Not Supported");
        }

        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if(gyroscope != null) {
            sensorManager.registerListener( this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "onCreate: Registered gyroscope listener");
        } else {
            Log.d(TAG, "Gyroscope Not Supported");
        }
    }

    private void stop()
    {
        new Thread(() -> {
            try
            {
                sendThread.interrupt();
                client.closeNow();
                System.out.println("Shutting down...");
            }
            catch (Exception e)
            {
                lastException = "Exception while closing IoTHub connection: " + e;
                handler.post(exceptionRunnable);
            }
        }).start();
    }

    public void btnStopOnClick(View v)
    {
        stop();

        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void start()
    {
        sendThread = new Thread(() -> {
            try
            {
                initClient();
                for(;;)
                {
                    sendMessages();
                    Thread.sleep(sendMessagesInterval);
                }
            }
            catch (InterruptedException e)
            {
                return;
            }
            catch (Exception e)
            {
                lastException = "Exception while opening IoTHub connection: " + e;
                handler.post(exceptionRunnable);
            }
        });

        sendThread.start();
    }

    public void btnStartOnClick(View v)
    {
        start();

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
    }

    final Runnable updateRunnable = new Runnable() {
        public void run() {
            txtLastAccelVal.setText(String.format("%.2f",accelX) + ", " + String.format("%.2f",accelY) + ", " + String.format("%.2f",accelZ));
            txtLastGyroscopeVal.setText(String.format("%.2f",gyroX) + ", " + String.format("%.2f",gyroY) + ", " + String.format("%.2f",gyroZ));
            txtMsgsSentVal.setText(Integer.toString(msgSentCount));
            txtLastMsgSentVal.setText("[" + new String(sendMessage.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET) + "]");
        }
    };

    final Runnable exceptionRunnable = new Runnable() {
        public void run() {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setMessage(lastException);
            builder.show();
            System.out.println(lastException);
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        }
    };


    final Runnable methodNotificationRunnable = () -> {
        Context context = getApplicationContext();
        CharSequence text = "Set Send Messages Interval to " + sendMessagesInterval + "ms";
        int duration = Toast.LENGTH_LONG;

        Toast toast = Toast.makeText(context, text, duration);
        toast.show();
    };

    private void sendMessages()
    {
        //temperature = 20.0 + Math.random() * 10;
        //humidity = 30.0 + Math.random() * 20;
        //String msgStr = "\"temperature\":" + String.format("%.2f", temperature) + ", \"humidity\":" + String.format("%.2f", humidity);
        String msgStr = "\"acceleration\":{\"x\":" + String.format("%f",accelX) + ", \"y\":" + String.format("%f",accelY) + ",\"z\":" + String.format("%f",accelZ) +
                "},\"gyroscope\":{\"x\":" + String.format("%f",gyroX) + ",\"y\":" + String.format("%f",gyroY) + ",\"z\":" + String.format("%f",gyroZ) +
                "},\"gps_coordinates\":{\"latitude\":" + String.format("%f",gpsLat) + ",\"longitude\":" + String.format("%f",gpsLong) + "}";
        Log.d("msgStr", msgStr);

        try
        {
            sendMessage = new Message(msgStr);
            //sendMessage.setProperty("temperatureAlert", temperature > 28 ? "true" : "false");

            sendMessage.setProperty("level", "storage");

            sendMessage.setMessageId(java.util.UUID.randomUUID().toString());
            System.out.println("Message Sent: " + msgStr);
            EventCallback eventCallback = new EventCallback();
            client.sendEventAsync(sendMessage, eventCallback, msgSentCount);
            msgSentCount++;
            handler.post(updateRunnable);
        }
        catch (Exception e)
        {
            System.err.println("Exception while sending event: " + e);
        }
    }

    private void initClient() throws URISyntaxException, IOException
    {
        client = new DeviceClient(connString, protocol);

        try
        {
            client.registerConnectionStatusChangeCallback(new IotHubConnectionStatusChangeCallbackLogger(), new Object());
            client.open();
            MessageCallback callback = new MessageCallback();
            client.setMessageCallback(callback, null);
            client.subscribeToDeviceMethod(new SampleDeviceMethodCallback(), getApplicationContext(), new DeviceMethodStatusCallBack(), null);
        }
        catch (Exception e)
        {
            System.err.println("Exception while opening IoTHub connection: " + e);
            client.closeNow();
            System.out.println("Shutting down...");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            // update global variables
            accelX = event.values[0];
            accelY = event.values[1];
            accelZ = event.values[2];

            txt_accelX.setText("accelX: m/s²\n" + String.format("%3.2f", event.values[0]));
            txt_accelY.setText("accelY: m/s²\n" + String.format("%3.2f", event.values[1]));
            txt_accelZ.setText("accelZ: m/s²\n" + String.format("%3.2f", event.values[2]));
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // update global variables
            gyroX = event.values[0] * 180/Math.PI;
            gyroY = event.values[1] * 180/Math.PI;
            gyroZ = event.values[2] * 180/Math.PI;

            txt_gyroX.setText("gyroX: °\n" + String.format("%3.2f",event.values[0] * 180/Math.PI));
            txt_gyroY.setText("gyroY: °\n" + String.format("%3.2f",event.values[1] * 180/Math.PI));
            txt_gyroZ.setText("gyroZ: °\n" + String.format("%3.2f",event.values[2] * 180/Math.PI));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    class EventCallback implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            Integer i = context instanceof Integer ? (Integer) context : 0;
            System.out.println("IoT Hub responded to message " + i
                    + " with status " + status.name());

            if((status == IotHubStatusCode.OK) || (status == IotHubStatusCode.OK_EMPTY))
            {
                TextView txtReceiptsConfirmedVal = findViewById(R.id.txtReceiptsConfirmedVal);
                receiptsConfirmedCount++;
                txtReceiptsConfirmedVal.setText(Integer.toString(receiptsConfirmedCount));
            }
            else
            {
                TextView txtSendFailuresVal = findViewById(R.id.txtSendFailuresVal);
                sendFailuresCount++;
                txtSendFailuresVal.setText(Integer.toString(sendFailuresCount));
            }
        }
    }

    class MessageCallback implements com.microsoft.azure.sdk.iot.device.MessageCallback
    {
        public IotHubMessageResult execute(Message msg, Object context)
        {
            System.out.println(
                    "Received message with content: " + new String(msg.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET));
            msgReceivedCount++;
            TextView txtMsgsReceivedVal = findViewById(R.id.txtMsgsReceivedVal);
            txtMsgsReceivedVal.setText(Integer.toString(msgReceivedCount));
            txtLastMsgReceivedVal.setText("[" + new String(msg.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET) + "]");
            return IotHubMessageResult.COMPLETE;
        }
    }

    protected static class IotHubConnectionStatusChangeCallbackLogger implements IotHubConnectionStatusChangeCallback
    {
        @Override
        public void execute(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable, Object callbackContext)
        {
            System.out.println();
            System.out.println("CONNECTION STATUS UPDATE: " + status);
            System.out.println("CONNECTION STATUS REASON: " + statusChangeReason);
            System.out.println("CONNECTION STATUS THROWABLE: " + (throwable == null ? "null" : throwable.getMessage()));
            System.out.println();

            if (throwable != null)
            {
                throwable.printStackTrace();
            }

            if (status == IotHubConnectionStatus.DISCONNECTED)
            {
                //connection was lost, and is not being re-established. Look at provided exception for
                // how to resolve this issue. Cannot send messages until this issue is resolved, and you manually
                // re-open the device client
            }
            else if (status == IotHubConnectionStatus.DISCONNECTED_RETRYING)
            {
                //connection was lost, but is being re-established. Can still send messages, but they won't
                // be sent until the connection is re-established
            }
            else if (status == IotHubConnectionStatus.CONNECTED)
            {
                //Connection was successfully re-established. Can send messages.
            }
        }
    }

    private int method_setSendMessagesInterval(Object methodData) throws JSONException
    {
        String payload = new String((byte[])methodData, StandardCharsets.UTF_8).replace("\"", "");
        JSONObject obj = new JSONObject(payload);
        sendMessagesInterval = obj.getInt("sendInterval");
        handler.post(methodNotificationRunnable);
        return METHOD_SUCCESS;
    }

    private int method_default(Object data)
    {
        System.out.println("invoking default method for this device");
        // Insert device specific code here
        return METHOD_NOT_DEFINED;
    }

    protected class DeviceMethodStatusCallBack implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            System.out.println("IoT Hub responded to device method operation with status " + status.name());
        }
    }

    protected class SampleDeviceMethodCallback implements com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodCallback
    {
        @Override
        public DeviceMethodData call(String methodName, Object methodData, Object context)
        {
            DeviceMethodData deviceMethodData ;
            try {
                switch (methodName) {
                    case "setSendMessagesInterval": {
                        int status = method_setSendMessagesInterval(methodData);
                        deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                        break;
                    }
                    default: {
                        int status = method_default(methodData);
                        deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                    }
                }
            }
            catch (Exception e)
            {
                int status = METHOD_THROWS;
                deviceMethodData = new DeviceMethodData(status, "Method Throws " + methodName);
            }
            return deviceMethodData;
        }
    }
}