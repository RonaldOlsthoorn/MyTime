package nl.senseos.mytimeatsense;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

import nl.senseos.mytimeatsense.CommonSenseConstants.Auth;
import nl.senseos.mytimeatsense.CommonSenseConstants.Sensors;
import nl.senseos.mytimeatsense.CommonSenseConstants.SenseDataTypes;
import nl.senseos.mytimeatsense.CommonSenseConstants.Url;

import org.apache.http.conn.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * @author Ronald Olsthoorn
 *
 */
public class CommonSenseAdapter {

	private static SharedPreferences sAuthPrefs;
	private static SharedPreferences sMainPrefs;
	private static final long CACHE_REFRESH = 1000l * 60 * 60; // 1 hour
	private static final String TAG = "Common Sense Adapter";

	/**
	 * Key for getting the http response code from the Map object that is
	 * returned by {@link SenseApi#request(Context, String, JSONObject, String)}
	 */
	public static final String RESPONSE_CODE = "http response code";
	/**
	 * Key for getting the response content from the Map object that is returned
	 * by {@link SenseApi#request(Context, String, JSONObject, String)}
	 */
	public static final String RESPONSE_CONTENT = "content";
	private Context context;

	/**
	 * Device UUID for sensors that are not physical sensors, i.e. not connected
	 * to any device
	 */
	public static final String NO_DEVICE_UUID = "no_device_uuid";

	private TelephonyManager sTelManager;

	public CommonSenseAdapter(Context c) {

		context = c;
	}

	/**
	 * Checks
	 * 
	 * @param context
	 * @return true if the account has a time series sensor. If sensor is
	 *         present, store the JSONOBject in the sharedpreferences. False
	 *         otherwise.
	 * @throws IOException
	 * @throws JSONException
	 */
	public boolean hasBeaconSensor() throws IOException, JSONException {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		JSONArray sensors = getAllSensors(context);

		if (sensors.length() == 0) {
			return false;
		}

		for (int i = 0; i < sensors.length(); i++) {

			if (sensors.getJSONObject(i).getString(Sensors.SENSOR_NAME)
					.equals(Sensors.BEACON_SENSOR_NAME)) {

				// store the new sensor list
				Editor authEditor = sAuthPrefs.edit();
				authEditor.putString(Sensors.BEACON_SENSOR, sensors
						.getJSONObject(i).toString());
				authEditor.commit();

				Log.d(TAG, "Beacon sensor present");

				return true;
			}
		}
		return false;
	}

	/**
	 * Gets a list of all registered sensors for a user at the CommonSense API.
	 * Uses caching for increased performance.
	 * 
	 * @param context
	 *            Application context, used for getting preferences.
	 * @return The list of sensors
	 * @throws IOException
	 *             In case of communication failure to CommonSense
	 * @throws JSONException
	 *             In case of unparseable response from CommonSense
	 */
	public JSONArray getAllSensors(Context context) throws IOException,
			JSONException {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		// try to get list of sensors from the cache
		try {
			String cachedSensors = sAuthPrefs.getString(
					Sensors.SENSOR_LIST_COMPLETE, null);
			long cacheTime = sAuthPrefs.getLong(
					Sensors.SENSOR_LIST_COMPLETE_TIME, 0);
			boolean isOutdated = System.currentTimeMillis() - cacheTime > CACHE_REFRESH;

			// return cached list of it is still valid
			if (false == isOutdated && null != cachedSensors) {
				return new JSONArray(cachedSensors);
			}

		} catch (Exception e) {
			// unlikely to ever happen. Just get the list from CommonSense
			// instead
			Log.w(TAG, "Failed to get list of sensors from cache!", e);
		}

		// if we make it here, the list was not in the cache
		Log.v(TAG, "List of sensor IDs is missing or outdated, refreshing...");

		boolean done = false;
		JSONArray result = new JSONArray();
		int page = 0;
		while (!done) {
			// request fresh list of sensors for this device from CommonSense
			String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

			String url = Url.SENSORS_URL;
			Map<String, String> response = request(context, url, null, cookie);

			String responseCode = response.get(RESPONSE_CODE);
			if (!"200".equals(responseCode)) {
				Log.w(TAG, "Failed to get list of sensors! Response code: "
						+ responseCode);
				throw new IOException("Incorrect response from CommonSense: "
						+ responseCode);
			}

			// parse response and store the list
			JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));
			JSONArray sensorList = content.getJSONArray("sensors");

			// put the sensor list in the result array
			for (int i = 0; i < sensorList.length(); i++) {
				result.put(sensorList.getJSONObject(i));
			}

			if (sensorList.length() < Sensors.PAGE_SIZE) {
				// all sensors received
				done = true;
			} else {
				// get the next page
				page++;
			}
		}

		// store the new sensor list
		Editor authEditor = sAuthPrefs.edit();
		authEditor.putString(Sensors.SENSOR_LIST_COMPLETE, result.toString());
		authEditor.putLong(Sensors.SENSOR_LIST_COMPLETE_TIME,
				System.currentTimeMillis());
		authEditor.commit();

		return result;
	}

	/**
	 * Register beacon sensor at common sense
	 * 
	 * @return true if registration succeeded, false otherwise
	 * @throws JSONException
	 * @throws IOException
	 */
	public boolean registerBeaconSensor() throws JSONException, IOException {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String id = registerSensor(context, Sensors.BEACON_SENSOR_NAME,
				Sensors.BEACON_SENSOR_DISPLAY_NAME,
				Sensors.BEACON_SENSOR_DESCRIPTION,
				CommonSenseConstants.SenseDataTypes.JSON_TIME_SERIES,
				"{\"total_time\":1,\"status\":false}", null, null);

		if (id == null) {
			return false;
		}

		JSONArray sensors = new JSONArray(sAuthPrefs.getString(
				Sensors.SENSOR_LIST_COMPLETE, null));

		if (sensors.length() == 0) {
			return false;
		}

		for (int i = 0; i < sensors.length(); i++) {

			if (sensors.getJSONObject(i).getInt(Sensors.SENSOR_ID) == Integer
					.parseInt(id)) {

				// store the new sensor list
				Editor authEditor = sAuthPrefs.edit();
				authEditor.putString(Sensors.BEACON_SENSOR, sensors
						.getJSONObject(i).toString());
				authEditor.commit();

				Log.d(TAG, "Beacon sensor present");

				return true;
			}
		}
		return true;
	}

	/**
	 * Registers a new sensor for this device at CommonSense. Also connects the
	 * sensor to this device.
	 * 
	 * @param context
	 *            The application context, used to retrieve preferences.
	 * @param name
	 *            The name of the sensor.
	 * @param displayName
	 *            The sensor's pretty display name.
	 * @param description
	 *            The sensor description (previously "device_type").
	 * @param dataType
	 *            The sensor data type.
	 * @param value
	 *            An example sensor value, used to determine the data structure
	 *            for JSON type sensors.
	 * @param deviceType
	 *            (Optional) Type of the device that holds the sensor. Set null
	 *            to use the default device.
	 * @param deviceUuid
	 *            (Optional) UUID of the device that holds the sensor. Set null
	 *            to use the default device.
	 * @return The new sensor ID at CommonSense, or <code>null</code> if the
	 *         registration failed.
	 * @throws JSONException
	 *             In case of invalid sensor details or if the request returned
	 *             unparseable response.
	 * @throws IOException
	 *             In case of communication failure during creation of the
	 *             sensor.
	 */
	public String registerSensor(Context context, String name,
			String displayName, String description, String dataType,
			String value, String deviceType, String deviceUuid)
			throws JSONException, IOException {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

		// prepare request to create new sensor
		String url = Url.SENSORS_URL;
		JSONObject postData = new JSONObject();
		JSONObject sensor = new JSONObject();
		sensor.put("name", name);
		sensor.put("device_type", description);
		sensor.put("display_name", displayName);
		sensor.put("pager_type", "");
		sensor.put("data_type", dataType);

		if (dataType.compareToIgnoreCase(SenseDataTypes.JSON) == 0
				|| dataType
						.compareToIgnoreCase(SenseDataTypes.JSON_TIME_SERIES) == 0) {
			JSONObject dataStructJSon = null;
			try {
				dataStructJSon = new JSONObject(value);
				JSONArray fieldNames = dataStructJSon.names();

				for (int x = 0; x < fieldNames.length(); x++) {
					String fieldName = fieldNames.getString(x);
					int start = dataStructJSon.get(fieldName).getClass()
							.getName().lastIndexOf(".");
					dataStructJSon.put(fieldName, dataStructJSon.get(fieldName)
							.getClass().getName().substring(start + 1));
				}
			} catch (JSONException e) {
				// apparently the data structure cannot be parsed from the value
				dataStructJSon = new JSONObject();
			}
			sensor.put("data_structure",
					dataStructJSon.toString().replaceAll("\"", "\\\""));

		}
		postData.put("sensor", sensor);

		Log.v(TAG, "postData: " + postData.toString(1));

		// perform actual request
		Map<String, String> response = request(context, url, postData, cookie);

		// check response code
		String code = response.get(RESPONSE_CODE);
		if (!"201".equals(code)) {
			Log.w(TAG,
					"Failed to register sensor at CommonSense! Response code: "
							+ code);
			throw new IOException("Incorrect response from CommonSense: "
					+ code);
		}

		// retrieve the newly created sensor ID
		String locationHeader = response.get("location");
		String[] split = locationHeader.split("/");
		String id = split[split.length - 1];

		// see if sensor should also be connected to a device at CommonSense
		if (NO_DEVICE_UUID.equals(deviceUuid)) {
			JSONObject device = new JSONObject();
			device.put("type", deviceType);
			device.put("uuid", deviceUuid);
			postData.put("device", device);
			// store the new sensor in the preferences
			sensor.put("id", id);
			sensor.put("device", device);
			JSONArray sensors = getAllSensors(context);
			sensors.put(sensor);
			Editor authEditor = sAuthPrefs.edit();
			authEditor.putString(Sensors.SENSOR_LIST_COMPLETE,
					sensors.toString());
			authEditor.commit();
			return id;
		}

		// get device properties from preferences, so it matches the properties
		// in CommonSense
		if (null == deviceUuid) {
			deviceUuid = getDefaultDeviceUuid(context);
			deviceType = getDefaultDeviceType(context);
		}

		// add sensor to this device at CommonSense
		url = Url.SENSOR_DEVICE_URL;
		url = url.replaceFirst("%1", id);
		postData = new JSONObject();
		JSONObject device = new JSONObject();
		device.put("type", deviceType);
		device.put("uuid", deviceUuid);
		postData.put("device", device);

		response = request(context, url, postData, cookie);

		// check response code
		code = response.get(RESPONSE_CODE);
		if (!"201".equals(code)) {
			Log.w(TAG,
					"Failed to add sensor to device at CommonSense! Response code: "
							+ code);
			throw new IOException("Incorrect response from CommonSense: "
					+ code);
		}

		// store the new sensor in the preferences
		sensor.put("id", id);
		sensor.put("device", device);

		Log.v(TAG, response.toString());

		JSONArray sensors = getAllSensors(context);
		sensors.put(sensor);

		Editor authEditor = sAuthPrefs.edit();
		authEditor.putString(Sensors.SENSOR_LIST_COMPLETE, sensors.toString());
		authEditor.commit();

		Log.v(TAG, "Created sensor: '" + name + "' for device: '" + deviceType
				+ "'");

		// return the new sensor ID
		return id;
	}

	/**
	 * login using the last used credentials.
	 * 
	 * @return
	 */
	public int login() {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String mUsername = sAuthPrefs.getString(Auth.PREFS_CREDS_UNAME, null);
		String mPassword = sAuthPrefs
				.getString(Auth.PREFS_CREDS_PASSWORD, null);

		return login(mUsername, mPassword);
	}

	/**
	 * 
	 * @param username
	 * @param password
	 * @return 0 if login succeeded, -1 if an exception occured and -2 if the
	 *         authentication failed (wrong credentials)
	 */
	public int login(String username, String password) {

		Map<String, String> response;
		// params comes from the execute() call: params[0] is the url.
		final JSONObject user = new JSONObject();
		try {
			user.put("username", username);
			user.put("password", hashPassword(password));
			response = request(context, Url.LOGIN_URL, user, null);

		} catch (Exception e) {
			Log.w(TAG, "Exception during login! " + e + ": '" + e.getMessage()
					+ "'");
			// handle result below
			return -1;
		}

		// if response code is not 200 (OK), the login was incorrect
		String responseCode = response.get(RESPONSE_CODE);
		int result = -1;
		if ("403".equalsIgnoreCase(responseCode)) {
			Log.w(TAG, "CommonSense login refused! Response: forbidden!");
			result = -2;
		} else if (!"200".equalsIgnoreCase(responseCode)) {
			Log.w(TAG, "CommonSense login failed! Response: " + responseCode);
			result = -1;
		} else {
			// received 200 response
			result = 0;
		}

		// create a cookie from the session_id
		String session_id = response.get("x-session_id");
		String cookie = "";
		if (result == 0 && session_id == null) {
			// something went horribly wrong
			Log.w(TAG, "CommonSense login failed: no cookie received?!");
			result = -1;
		} else
			cookie = "session_id=" + session_id + "; domain=.sense-os.nl";

		// handle result
		Editor authEditor = context.getSharedPreferences(Auth.PREFS_CREDS,
				Context.MODE_PRIVATE).edit();
		switch (result) {
		case 0: // logged in
			authEditor.putString(Auth.LOGIN_COOKIE, cookie);
			authEditor.putString(Auth.LOGIN_SESSION_ID, session_id);
			authEditor.putString(Auth.PREFS_CREDS_UNAME, username);
			authEditor.putString(Auth.PREFS_CREDS_PASSWORD, password);
			authEditor.commit();
			break;
		case -1: // error
			break;
		case -2: // unauthorized
			authEditor.remove(Auth.LOGIN_COOKIE);
			authEditor.remove(Auth.LOGIN_SESSION_ID);
			authEditor.commit();
			break;
		default:
			Log.e(TAG, "Unexpected login result: " + result);
		}
		return result;
	}

	/**
	 * logout of the current session
	 * 
	 * @return 0 if the logout succeeded, -1 if an exception occured
	 * @throws IOException
	 */
	public int logout() throws IOException {

		Map<String, String> response;
		// params comes from the execute() call: params[0] is the url.

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
		response = request(context, Url.LOGOUT_URL, null, cookie);

		// if response code is not 200 (OK), the login was incorrect
		String responseCode = response.get(RESPONSE_CODE);
		int result = -1;
		if (!"200".equalsIgnoreCase(responseCode)) {
			Log.w(TAG, "CommonSense logout failed! Response: " + responseCode);
			result = -1;
		} else {
			// received 200 response
			result = 0;
			Log.v(TAG, "Logout successfull");
		}

		// handle result
		Editor authEditor = context.getSharedPreferences(Auth.PREFS_CREDS,
				Context.MODE_PRIVATE).edit();
		switch (result) {
		case 0: // logged out

			authEditor.remove(Auth.LOGIN_COOKIE);
			authEditor.remove(Auth.LOGIN_SESSION_ID);
			authEditor.commit();
			break;
		case -1: // error
			break;
		default:
			Log.e(TAG, "Unexpected logout result: " + result);
		}
		return result;
	}

	/**
	 * 
	 * @param beaconDetected
	 *            true if a beacon was detected, false otherwise
	 * @param timestamp
	 *            timestamp of bluetooth scan
	 * @return
	 * @throws JSONException
	 * @throws IOException
	 */
	public int sendBeaconData(long timestamp, long totalTime, boolean status)
			throws JSONException, IOException {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
		String beaconSensorString = sAuthPrefs.getString(Sensors.BEACON_SENSOR,
				null);

		JSONObject beaconSensor = new JSONObject(beaconSensorString);

		int sensorId = (int) beaconSensor.getLong("id");
		String url = Url.SENSORS_URL + "/" + sensorId + "/data";

		String value = "{\"total_time\":" + totalTime + "," + "\"status\":"
				+ status + "}";
		JSONArray data = new JSONArray();

		data.put(0, new JSONObject().put("date", timestamp).put("value", value));

		JSONObject postData = new JSONObject();
		postData.put("data", data);

		Map<String, String> response = request(context, url, postData, cookie);

		// check response code
		String code = response.get(RESPONSE_CODE);

		int result = -1;
		if ("403".equalsIgnoreCase(code)) {
			Log.w(TAG,
					"CommonSense authentication while uploading data Response: forbidden!");
			result = -2;
		}
		if (!"201".equals(code)) {
			Log.w(TAG, "response " + code);
			result = -1;
			throw new IOException("Incorrect response from CommonSense: "
					+ code);
		}
		if ("201".equals(code)) {
			Log.w(TAG, "Upload successful: " + code);
			result = 0;
		}
		return result;
	}

	/**
	 * Deprecated
	 */
	public int sendDataChair(int maj, int min, long timestamp)
			throws JSONException, IOException {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
		String sensorsPrefs = sAuthPrefs.getString(
				Sensors.SENSOR_LIST_COMPLETE, null);

		JSONArray sensorListComplete = new JSONArray(sensorsPrefs);
		JSONObject chairSensor = sensorListComplete.getJSONObject(0);

		int sensorId = (int) chairSensor.getLong("id");
		String url = Url.SENSORS_URL + "/" + sensorId + "/data";

		String value = "{\"Major\":" + maj + ",\"Minor\":" + min + "}";
		JSONArray data = new JSONArray();

		data.put(0, new JSONObject().put("date", timestamp).put("value", value));

		JSONObject postData = new JSONObject();
		postData.put("data", data);

		Map<String, String> response = request(context, url, postData, cookie);

		// check response code
		String code = response.get(RESPONSE_CODE);

		int result = -1;
		if ("403".equalsIgnoreCase(code)) {
			Log.w(TAG,
					"CommonSense authentication while uploading data Response: forbidden!");
			result = -2;
		}
		if (!"201".equals(code)) {
			Log.w(TAG, "Success: " + code);
			result = -1;
			throw new IOException("Incorrect response from CommonSense: "
					+ code);
		}
		if ("201".equals(code)) {
			Log.w(TAG, "Upload successful: " + code);
			result = 0;
		}
		return result;
	}

	public JSONObject fetchTotalTime() throws IOException, JSONException {

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
		String beaconSensorString = sAuthPrefs.getString(Sensors.BEACON_SENSOR,
				null);

		JSONObject beaconSensor = new JSONObject(beaconSensorString);

		int sensorId = (int) beaconSensor.getLong("id");
		String url = Url.SENSORS_URL + "/" + sensorId + "/data" + "?last=true";

		Map<String, String> response = request(context, url, null, cookie);
		String responseCode = response.get(RESPONSE_CODE);

		JSONObject result = new JSONObject();

		if ("403".equalsIgnoreCase(responseCode)) {
			Log.w(TAG,
					"CommonSense authentication while downloading data Response: forbidden!");

		}
		if ("200".equals(responseCode)) {
			Log.w(TAG, "Download successful: " + responseCode);

			// parse response and store the list
			JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));

			if (content.getJSONArray("data").length() == 0) {
				return null;
			}
			JSONArray dataList = content.getJSONArray("data");
			return dataList.getJSONObject(0);
		}

		else {
			Log.w(TAG, "responsecode: " + responseCode);
			throw new IOException("Incorrect response from CommonSense: "
					+ responseCode);
		}
	}

	public JSONObject fetchTimeToday() throws IOException, JSONException {

		GregorianCalendar cCurrent = new GregorianCalendar();
		
		GregorianCalendar todayMidnight = new GregorianCalendar(
				cCurrent.get(Calendar.YEAR),
				cCurrent.get(Calendar.MONTH),
				cCurrent.get(Calendar.DAY_OF_MONTH), 0, 0);
		
		todayMidnight.set(GregorianCalendar.HOUR, 0);
		todayMidnight.set(GregorianCalendar.MINUTE, 0);
		todayMidnight.set(GregorianCalendar.SECOND, 0);
				
		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
		String beaconSensorString = sAuthPrefs.getString(Sensors.BEACON_SENSOR,
				null);

		JSONObject beaconSensor = new JSONObject(beaconSensorString);

		Log.e(TAG, "todayMidnight: "+todayMidnight.getTimeInMillis() / 1000);
		
		int sensorId = (int) beaconSensor.getLong("id");
		String url = Url.SENSORS_URL + "/" + sensorId + "/data"
				+ "?end_date=" + todayMidnight.getTimeInMillis() / 1000
				+ "&last=true";

		Map<String, String> response = request(context, url, null, cookie);
		String responseCode = response.get(RESPONSE_CODE);

		JSONObject result = new JSONObject();

		if ("403".equalsIgnoreCase(responseCode)) {
			Log.w(TAG,
					"CommonSense authentication while downloading data Response: forbidden!");

		}
		if ("200".equals(responseCode)) {
			Log.w(TAG, "Download successful: " + responseCode);

			// parse response and store the list
			JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));

			if (content.getJSONArray("data").length() == 0) {
				return null;
			}
			JSONArray dataList = content.getJSONArray("data");
			return dataList.getJSONObject(0);
		}

		else {
			Log.w(TAG, "responsecode: " + responseCode);
			throw new IOException("Incorrect response from CommonSense: "
					+ responseCode);
		}
	}

	public JSONObject fetchTimeThisWeek() throws IOException, JSONException {

		GregorianCalendar cCurrent = new GregorianCalendar();
		
		GregorianCalendar c = new GregorianCalendar(
				cCurrent.get(Calendar.YEAR),
				cCurrent.get(Calendar.MONTH),
				cCurrent.get(Calendar.DAY_OF_MONTH), 0, 0);
		c.set(Calendar.DAY_OF_WEEK, 0);
		
		GregorianCalendar startWeekMidnight = new GregorianCalendar();
		startWeekMidnight.set(GregorianCalendar.DAY_OF_WEEK, 1);
		startWeekMidnight.set(GregorianCalendar.HOUR, 0);
		startWeekMidnight.set(GregorianCalendar.MINUTE, 0);
		startWeekMidnight.set(GregorianCalendar.SECOND, 0);

		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}

		String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
		String beaconSensorString = sAuthPrefs.getString(Sensors.BEACON_SENSOR,
				null);

		JSONObject beaconSensor = new JSONObject(beaconSensorString);

		Log.d(TAG, "startWeekMidnight: "+startWeekMidnight.getTimeInMillis() / 1000);
		
		int sensorId = (int) beaconSensor.getLong("id");
		String url = Url.SENSORS_URL + "/" + sensorId + "/data" + "?end_date="
				+ startWeekMidnight.getTimeInMillis() / 1000 + "&last=true";

		Map<String, String> response = request(context, url, null, cookie);
		String responseCode = response.get(RESPONSE_CODE);

		JSONObject result = new JSONObject();

		if ("403".equalsIgnoreCase(responseCode)) {
			Log.w(TAG,
					"CommonSense authentication while downloading data Response: forbidden!");

		}
		if ("200".equals(responseCode)) {
			Log.w(TAG, "Download successful: " + responseCode);

			// parse response and store the list
			JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));

			if (content.getJSONArray("data").length() == 0) {
				return null;
			}
			JSONArray dataList = content.getJSONArray("data");
			return dataList.getJSONObject(0);
		}

		else {
			Log.w(TAG, "responsecode: " + responseCode);
			throw new IOException("Incorrect response from CommonSense: "
					+ responseCode);
		}
	}

	/**
	 * Performs request at CommonSense API. Returns the response code, content,
	 * and headers.
	 * 
	 * @param context
	 *            Application context, used to read preferences.
	 * @param urlString
	 *            Complete URL to perform request to.
	 * @param content
	 *            (Optional) Content for the request. If the content is not
	 *            null, the request method is automatically POST. The default
	 *            method is GET.
	 * @param cookie
	 *            (Optional) Cookie header for the request.
	 * @return Map with SenseApi.KEY_CONTENT and SenseApi.KEY_RESPONSE_CODE
	 *         fields, plus fields for all response headers.
	 * @throws IOException
	 */
	public Map<String, String> request(Context context, String urlString,
			JSONObject content, String cookie) throws IOException {

		HttpURLConnection urlConnection = null;
		HashMap<String, String> result = new HashMap<String, String>();
		try {

			boolean compress = true;

			// open new URL connection channel.
			URL url = new URL(urlString);
			if ("https".equals(url.getProtocol().toLowerCase(Locale.ENGLISH))) {
				HttpsURLConnection https = (HttpsURLConnection) url
						.openConnection();
				https.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
				urlConnection = https;
			} else {
				urlConnection = (HttpURLConnection) url.openConnection();
			}

			// some parameters
			urlConnection.setUseCaches(false);
			urlConnection.setInstanceFollowRedirects(false);
			urlConnection.setRequestProperty("Accept", "application/json");

			// set cookie (if available)
			if (null != cookie) {
				urlConnection.setRequestProperty("Cookie", cookie);
			}

			// set the application id
			// if (null != APPLICATION_KEY)
			// urlConnection.setRequestProperty("APPLICATION-KEY",
			// APPLICATION_KEY);

			// send content (if available)
			if (null != content) {
				urlConnection.setDoOutput(true);
				// When no charset is given in the Content-Type header
				// "ISO-8859-1" should be
				// assumed (see
				// http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.7.1).
				// Because we're uploading UTF-8 the charset should be set to
				// UTF-8.
				urlConnection.setRequestProperty("Content-Type",
						"application/json; charset=utf-8");

				// send content
				DataOutputStream printout;
				if (compress) {
					// do not set content size
					urlConnection.setRequestProperty("Transfer-Encoding",
							"chunked");
					urlConnection
							.setRequestProperty("Content-Encoding", "gzip");
					GZIPOutputStream zipStream = new GZIPOutputStream(
							urlConnection.getOutputStream());
					printout = new DataOutputStream(zipStream);
				} else {
					// set content size

					// The content length should be in bytes. We cannot use
					// string length here
					// because that counts the number of chars while not
					// accounting for multibyte
					// chars
					int contentLength = content.toString().getBytes("UTF-8").length;
					urlConnection.setFixedLengthStreamingMode(contentLength);
					urlConnection.setRequestProperty("Content-Length", ""
							+ contentLength);
					printout = new DataOutputStream(
							urlConnection.getOutputStream());
				}
				// Write the string in UTF-8 encoding
				printout.write(content.toString().getBytes("UTF-8"));
				printout.flush();
				printout.close();
			}

			// get response, or read error message
			InputStream inputStream;
			try {
				inputStream = urlConnection.getInputStream();
			} catch (IOException e) {
				inputStream = urlConnection.getErrorStream();
				e.printStackTrace();
			}
			if (null == inputStream) {
				throw new IOException("could not get InputStream");
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					inputStream), 1024);
			String line;
			StringBuffer responseContent = new StringBuffer();
			while ((line = reader.readLine()) != null) {
				responseContent.append(line);
				responseContent.append('\r');
			}
			result.put(RESPONSE_CONTENT, responseContent.toString());
			result.put(RESPONSE_CODE,
					Integer.toString(urlConnection.getResponseCode()));

			// clean up
			reader.close();
			reader = null;
			inputStream.close();
			inputStream = null;

			// get headers
			Map<String, List<String>> headerFields = urlConnection
					.getHeaderFields();
			String key, valueString;
			List<String> value;
			for (Entry<String, List<String>> entry : headerFields.entrySet()) {
				key = entry.getKey();
				value = entry.getValue();
				if (null != key && null != value) {
					key = key.toLowerCase(Locale.ENGLISH);
					valueString = value.toString();
					valueString = valueString.substring(1,
							valueString.length() - 1);
					result.put(key, valueString);
				}
			}

			return result;

		} finally {
			if (urlConnection != null) {
				urlConnection.disconnect();
			}
		}
	}

	/**
	 * @param hashMe
	 *            "clear" password String to be hashed before sending it to
	 *            CommonSense
	 * @return Hashed String
	 */
	public static String hashPassword(String hashMe) {
		final byte[] unhashedBytes = hashMe.getBytes();
		try {
			final MessageDigest algorithm = MessageDigest.getInstance("MD5");
			algorithm.reset();
			algorithm.update(unhashedBytes);
			final byte[] hashedBytes = algorithm.digest();

			final StringBuffer hexString = new StringBuffer();
			for (final byte element : hashedBytes) {
				final String hex = Integer.toHexString(0xFF & element);
				if (hex.length() == 1) {
					hexString.append(0);
				}
				hexString.append(hex);
			}
			return hexString.toString();
		} catch (final NoSuchAlgorithmException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * @param context
	 *            Context for accessing phone details
	 * @return The default device UUID, e.g. the phone's IMEI String
	 */
	@TargetApi(9)
	public String getDefaultDeviceUuid(Context context) {
		if (null == sTelManager) {
			sTelManager = ((TelephonyManager) context
					.getSystemService(Context.TELEPHONY_SERVICE));
		}
		String uuid = sTelManager.getDeviceId();
		if (null == uuid) {
			// device has no IMEI, try using the Android serial code
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
				uuid = Build.SERIAL;
			} else {
				Log.w(TAG, "Cannot get reliable device UUID!");
			}
		}
		return uuid;
	}

	/**
	 * @param context
	 *            Context for accessing phone details
	 * @return The default device type, i.e. the phone's model String
	 */
	public static String getDefaultDeviceType(Context context) {
		if (null == sAuthPrefs) {
			sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}
		return sAuthPrefs.getString(Auth.DEVICE_TYPE, Build.MODEL);
	}
}
