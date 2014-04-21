package com.example.sensorpoller;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

public class DataCollectionService extends Service implements LocationListener,SensorEventListener{
	private static final String LOG_TAG = DataCollectionService.class.getName();
	
	// - - - Polling Constants - - - //
	private static final int POLLING_INTERVAL_MILLIS = 30*1000;
	private static final int RECORDING_INTERVAL_MILLIS = 5*1000;
	
	// - - - Inertial Sensor Sampling Rate - - - //
	private static final int SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME;
	private Timer timer;
	
	// - - - System Service Managers - - - //
	private LocationManager mLocationManager;
	private SensorManager mSensorManager;
	private WifiManager mWifiManager;
	private WakeLock mWakeLock;
	private MediaRecorder mRecorder;
	
	// - - - Sensors - - - //
	private SparseArray<Sensor> mSensors;
	private static final int[] SENSOR_TYPES = new int[] {
		Sensor.TYPE_ACCELEROMETER,
		Sensor.TYPE_GYROSCOPE,
		Sensor.TYPE_MAGNETIC_FIELD,
		Sensor.TYPE_LIGHT,
		Sensor.TYPE_PROXIMITY,
		Sensor.TYPE_PRESSURE,
		// Following sensors require API Level 14 [ICE_CREAM_SANDWICH]
		Sensor.TYPE_ROTATION_VECTOR,
		Sensor.TYPE_RELATIVE_HUMIDITY,
		Sensor.TYPE_AMBIENT_TEMPERATURE,
		Sensor.TYPE_LINEAR_ACCELERATION,
		Sensor.TYPE_GRAVITY,
		// Sensor.TYPE_GAME_ROTATION_VECTOR, <deprecated>
	};
	
	// ----- Temporary Sensor Storage -----
		private float[] tmp_orientationRotation = new float[9];
		private float[] tmp_orientationInclination = new float[9];
		private float[] tmp_gravity = new float[3];
		private float[] tmp_geomag = new float[3];
		private float[] tmp_eulerAngles = new float[3];
	
	// ----- File Storage -----
	private File datalog;
	private FileOutputStream outputStream;
		
	@Override
	public void onCreate() {
		super.onCreate();
		initializeSensors();
		
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "DataCollectionService");
		mWakeLock.acquire();
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		mWakeLock.release();
	}
	
	private void initializeSensors(){
		mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
				
		mSensors = new SparseArray<Sensor>();
		for (int sensorType : SENSOR_TYPES) {
			Sensor sensor = mSensorManager.getDefaultSensor(sensorType);
			if (sensor != null) {
				mSensors.put(sensorType, sensor);		
			} else {
				Toast.makeText(this, String.format("Missing Sensor, ID: %1$d", sensorType), Toast.LENGTH_SHORT).show();
			}
		}
	}
	
	@SuppressLint("SimpleDateFormat")
	public void enablePolling(){
		Log.i(LOG_TAG, "Polling has been enabled.");
		/* Setup Location Manager - can't use requestSingleUpdate,
		 * 		GPS stabilization time is always greater than recording interval*/
		mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
		mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);

		// WIFI and cell tower information are retrieved using the two BroadcastReceiver classes
		//registerReceiver(mWifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

		// acquire a partial wake lock to prevent the phone from sleeping
		
		SimpleDateFormat s = new SimpleDateFormat("yyyyMMddhhmmss");
		File mOutputDir = new File (android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/datalogs/");
		// attempt to make output directory
		if (!mOutputDir.exists()) {
            mOutputDir.mkdirs();
        }
		datalog = new File(mOutputDir, "Sensors_" + s.format(new Date()) + ".log");
		try {
			outputStream = new FileOutputStream(datalog);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		mRecorder = new MediaRecorder();
		timer = new Timer();
		timer.schedule(new TimerTask(){
			@Override
			public void run() {
				Log.i(LOG_TAG, "Polling Sensors!");
				long timeMillis = System.currentTimeMillis();
				writeToExternal("- - - " + timeMillis + " - - -\n");
				int rstate = startRecording(timeMillis);
				pollStart();
				try{Thread.sleep(RECORDING_INTERVAL_MILLIS);}catch(Exception e){}
				Location lastLocation = mLocationManager.getLastKnownLocation(mLocationManager.GPS_PROVIDER);
				if(lastLocation != null){
					writeToExternal("100," + lastLocation.getTime() + "," + lastLocation.getLatitude() + "," + lastLocation.getLongitude() + "\n");
				}
				if(rstate == 0) {stopRecording();}
				pollStop();
				Log.i(LOG_TAG, "Stopping Sensors!");
			}
		}, 0, POLLING_INTERVAL_MILLIS);
	}
	
	public void disablePolling(){
		mLocationManager.removeUpdates(this);
		timer.cancel();
		timer.purge();
		try {
			outputStream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		mRecorder.release();
		mRecorder = null;
		Log.i(LOG_TAG, "Polling has been disabled.");
	}
	
	private void pollStart(){
		for (int sensorType : SENSOR_TYPES) {
			if(mSensors.get(sensorType) != null)
				mSensorManager.registerListener(this, mSensors.get(sensorType), SENSOR_DELAY);
		}
	}
	
	private void pollStop(){
		mSensorManager.unregisterListener(this);
	}
	
	private int startRecording(long time){
		Log.i(LOG_TAG, "Recording audio. . . ");
		mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
		mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
		mRecorder.setOutputFile(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Audio_" + time + ".3gpp");
		try{
			mRecorder.prepare();
		} catch (IOException e) {
			Log.e(LOG_TAG, "prepare() failed");
			return 1;
		}
		mRecorder.start();
		return 0;
	}
	
	private void stopRecording(){
		mRecorder.stop();
		mRecorder.reset();
		Log.i(LOG_TAG, ". . . stopped recording.");
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		int i = 0;
		int sensorType = event.sensor.getType();
		
		// write event type and time
		writeToExternal(sensorType + "," + event.timestamp + ",");
		
		// write all but last event value
		for( i=0; i < SensorInfo.getNumberOfAxes(sensorType) -1; i++ ){
			writeToExternal(event.values[i] + ",");
		}
		// write last value with a new line
		writeToExternal(event.values[i] + "\n");
		
		// store gravity and geomag to calculate Euler angles
		if( sensorType == Sensor.TYPE_GRAVITY ){
			tmp_gravity[0] = event.values[0];
			tmp_gravity[1] = event.values[1];
			tmp_gravity[2] = event.values[2];
		}else if( sensorType == Sensor.TYPE_MAGNETIC_FIELD){
			tmp_geomag[0] = event.values[0];
			tmp_geomag[1] = event.values[1];
			tmp_geomag[2] = event.values[2];
			
			getEulerAngles(tmp_eulerAngles);
			
			writeToExternal("101," + event.timestamp + "," + tmp_eulerAngles[0] + "," + tmp_eulerAngles[1] + "," + tmp_eulerAngles[2] + "\n");
		}
	}
	
	private void getEulerAngles(float[] buffer){
		// get rotation matrix
		SensorManager.getRotationMatrix(tmp_orientationRotation, tmp_orientationInclination, tmp_gravity, tmp_geomag);
		// get Euler angles
		SensorManager.getOrientation(tmp_orientationRotation, tmp_eulerAngles);
	}
	
	// ----- Checks if external storage is available for read and write -----
	public boolean isExternalStorageWritable() {
	    String state = Environment.getExternalStorageState();
	    if (Environment.MEDIA_MOUNTED.equals(state)) {
	        return true;
	    }
	    return false;
	}
		
	// ----- Write string to output log file -----
	private void writeToExternal(String data){
		if(!isExternalStorageWritable()){
			return;
		}
	    try {
	        outputStream.write(data.getBytes());
	    } catch (FileNotFoundException e) {
	        e.printStackTrace();
	        Log.d(LOG_TAG, "!!! Output file (External storage) not found !!!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }   
	}

	private final IBinder mBinder = new LocalBinder();

	// ----- Binding Function -----
	public class LocalBinder extends Binder {
		DataCollectionService getService() {
			return DataCollectionService.this;
		}
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}
	

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {}
	@Override
	public void onLocationChanged(Location location) {}
	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {}
	@Override
	public void onProviderEnabled(String provider) {}
	@Override
	public void onProviderDisabled(String provider) {}
}
