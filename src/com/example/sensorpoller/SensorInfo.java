package com.example.sensorpoller;

import android.hardware.Sensor;

public class SensorInfo {
	
	public static final int getNumberOfAxes(int sensorType) {
		switch (sensorType) {
		case Sensor.TYPE_AMBIENT_TEMPERATURE:
		case Sensor.TYPE_LIGHT:
		case Sensor.TYPE_PRESSURE:
		case Sensor.TYPE_RELATIVE_HUMIDITY:
		case Sensor.TYPE_PROXIMITY:
			return 1;
		case Sensor.TYPE_ACCELEROMETER:
		case Sensor.TYPE_GRAVITY:
		case Sensor.TYPE_GYROSCOPE:
		case Sensor.TYPE_LINEAR_ACCELERATION:
		case Sensor.TYPE_MAGNETIC_FIELD:
			return 3;
		//case Sensor.TYPE_GAME_ROTATION_VECTOR:
		//	return 4;
		case Sensor.TYPE_ROTATION_VECTOR:
			return 5;
		//case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
		//case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
		//	return 6;
		case Sensor.TYPE_ALL:
			throw new IllegalArgumentException("Sensor.TYPE_ALL is not allowed.");
		}
		
		throw new IllegalArgumentException("Unknown Sensor.TYPE");
	}
}
