package nl.senseos.mytimeatsense.commonsense;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

import nl.senseos.mytimeatsense.bluetooth.iBeacon;
import nl.senseos.mytimeatsense.storage.DBHelper;
import nl.senseos.mytimeatsense.util.Constants;
import nl.senseos.mytimeatsense.util.Constants.Auth;
import nl.senseos.mytimeatsense.util.Constants.GroupPrefs;
import nl.senseos.mytimeatsense.util.Constants.SenseDataTypes;
import nl.senseos.mytimeatsense.util.Constants.Sensors;
import nl.senseos.mytimeatsense.util.Constants.Url;
import nl.senseos.mytimeatsense.util.Constants.Labels;
import nl.senseos.mytimeatsense.util.Hash;

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
 *         Performs all calls to the CommonSense API
 */
public class CommonSenseAdapter {

    private SharedPreferences sAuthPrefs;
    private SharedPreferences sSensorPrefs;
    private SharedPreferences sLabelPrefs;

    private static final long CACHE_REFRESH = 1000l * 60 * 60; // 1 hour
    private static final String TAG = CommonSenseAdapter.class.getSimpleName();

    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_DELETE = "DELETE";

    /**
     * Key for getting the http response code from the Map object that is
     * returned by {request(Context, String, JSONObject, String)}
     */
    public static final String RESPONSE_CODE = "http response code";
    /**
     * Key for getting the response content from the Map object that is returned
     * by {request(Context, String, JSONObject, String)}
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
     * Checks if the user has a label in its list of labels. If so, stores the
     * JSON representation of the label in the SharedPreferences.
     *
     * @return true if the account has a . If label is
     * present, store the JSONObject in the SharedPreferences. False
     * otherwise.
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public boolean hasLabel() throws IOException, JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        JSONArray labels = getAllLabels();

        if (labels.length() == 0) {
            return false;
        }

        for (int i = 0; i < labels.length(); i++) {

            if (labels.getJSONObject(i).getString(Labels.LABEL_NAME)
                    .equals(Constants.Labels.LABEL_NAME_OFFICE)) {

                // store the new sensor list
                Editor authEditor = sAuthPrefs.edit();
                authEditor.putString(Labels.LABEL_OFFICE, labels
                        .getJSONObject(i).toString());
                authEditor.commit();

                Log.d(TAG, "Office Label present");

                return true;
            }
        }
        return false;
    }

    /**
     * Gets a list of all registered labels for a user at the CommonSense API.
     * Uses caching for increased performance.
     * <p/>
     * Application context, used for getting preferences.
     *
     * @return The list of sensors
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONArray getAllLabels() throws IOException,
            JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        // try to get list of labels from the cache. If exception occurs or cache is outdated
        // continue.
        try {
            String cachedLabels = sAuthPrefs.getString(
                    Labels.LABEL_LIST_COMPLETE, null);
            long cacheTime = sAuthPrefs.getLong(
                    Labels.LABEL_LIST_COMPLETE_TIME, 0);
            boolean isOutdated = System.currentTimeMillis() - cacheTime > CACHE_REFRESH;

            // return cached list of labels. it is still valid
            if (false == isOutdated && null != cachedLabels) {
                return new JSONArray(cachedLabels);
            }

        } catch (Exception e) {
            // unlikely to ever happen. Just get the list from CommonSense
            // instead
            Log.w(TAG, "Failed to get list of labels from cache!", e);
        }

        // if we make it here, the list was not in the cache
        Log.v(TAG, "List of label IDs is missing or outdated, refreshing...");

        JSONArray result = new JSONArray();

        // request fresh list of labels for this user from CommonSense
        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
        String url = Url.LABELS_URL + "/all";

        Map<String, String> response = request(url, METHOD_GET, null, cookie);

        String responseCode = response.get(RESPONSE_CODE);
        if (!"200".equals(responseCode)) {
            Log.w(TAG, "Failed to get list of sensors! Response code: "
                    + responseCode);
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);
        }

        // parse response and store the list
        JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));
        JSONArray labelList = content.getJSONArray("labels");

        // put the sensor list in the result array
        for (int i = 0; i < labelList.length(); i++) {
            result.put(labelList.getJSONObject(i));
        }

        // store the new sensor list
        Editor authEditor = sAuthPrefs.edit();
        authEditor.putString(Labels.LABEL_LIST_COMPLETE, result.toString());
        authEditor.putLong(Labels.LABEL_LIST_COMPLETE_TIME,
                System.currentTimeMillis());
        authEditor.commit();

        return result;
    }

    /**
     * Checks if the user has a beacon sensor in its list of sensors. If so, stores the
     * JSON representation of the sensor in the SharedPreferences.
     *
     * @return true if the account has a time series sensor. If sensor is
     * present, store the JSONOBject in the sharedpreferences. False
     * otherwise.
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public boolean hasBeaconSensor() throws IOException, JSONException {

        if (null == sSensorPrefs) {
            sSensorPrefs = context.getSharedPreferences(Sensors.PREFS_SENSORS,
                    Context.MODE_PRIVATE);
        }

        JSONArray sensors = getAllSensors();

        if (sensors.length() == 0) {
            return false;
        }

        for (int i = 0; i < sensors.length(); i++) {

            if (sensors.getJSONObject(i).getString(Sensors.SENSOR_NAME)
                    .equals(Sensors.BEACON_SENSOR_NAME)) {

                // store the new sensor list
                Editor sensorEditor = sSensorPrefs.edit();
                sensorEditor.putString(Sensors.BEACON_SENSOR, sensors
                        .getJSONObject(i).toString());
                sensorEditor.commit();

                Log.d(TAG, "Beacon sensor present");

                return true;
            }
        }
        return false;
    }

    /**
     * Gets a list of all registered sensors for a user at the CommonSense API.
     * Uses caching for increased performance.
     * Application context, used for getting preferences.
     *
     * @return The list of sensors
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONArray getAllSensors() throws IOException,
            JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        if (null == sSensorPrefs) {
            sSensorPrefs = context.getSharedPreferences(Sensors.PREFS_SENSORS,
                    Context.MODE_PRIVATE);
        }

        // try to get list of sensors from the cache
        try {
            String cachedSensors = sSensorPrefs.getString(
                    Sensors.SENSOR_LIST_COMPLETE, null);
            long cacheTime = sSensorPrefs.getLong(
                    Sensors.SENSOR_LIST_COMPLETE_TIME, 0);
            boolean isOutdated = System.currentTimeMillis() - cacheTime > CACHE_REFRESH;

            // return cached list of it is still valid
            if (!isOutdated && null != cachedSensors) {
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

            String url = Url.SENSORS_URL + "?page=" + page + "&per_page=" + Sensors.PAGE_SIZE;
            Map<String, String> response = request(url, METHOD_GET, null, cookie);

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
        Editor sensorEditor = sSensorPrefs.edit();
        sensorEditor.putString(Sensors.SENSOR_LIST_COMPLETE, result.toString());
        sensorEditor.putLong(Sensors.SENSOR_LIST_COMPLETE_TIME,
                System.currentTimeMillis());
        sensorEditor.commit();

        return result;
    }

    /**
     * Register beacon sensor at CommonSense
     *
     * @return true if registration succeeded, false otherwise
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public boolean registerBeaconSensor() throws JSONException, IOException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        if (null == sSensorPrefs) {
            sSensorPrefs = context.getSharedPreferences(Sensors.PREFS_SENSORS,
                    Context.MODE_PRIVATE);
        }

        String id = registerSensor(Sensors.BEACON_SENSOR_NAME,
                Sensors.BEACON_SENSOR_DISPLAY_NAME,
                Sensors.BEACON_SENSOR_DESCRIPTION,
                SenseDataTypes.JSON_TIME_SERIES,
                "{\"total_time\":1,\"status\":false}", null, null);

        if (id == null) {
            return false;
        }

        JSONArray sensors = new JSONArray(sSensorPrefs.getString(
                Sensors.SENSOR_LIST_COMPLETE, null));

        if (sensors.length() == 0) {
            return false;
        }

        for (int i = 0; i < sensors.length(); i++) {

            if (sensors.getJSONObject(i).getInt(Sensors.SENSOR_ID) == Integer
                    .parseInt(id)) {

                // store the new sensor list
                Editor sensorEditor = sSensorPrefs.edit();
                sensorEditor.putString(Sensors.BEACON_SENSOR, sensors
                        .getJSONObject(i).toString());
                sensorEditor.commit();

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
     * @param name        The name of the sensor.
     * @param displayName The sensor's pretty display name.
     * @param description The sensor description (previously "device_type").
     * @param dataType    The sensor data type.
     * @param value       An example sensor value, used to determine the data structure
     *                    for JSON type sensors.
     * @param deviceType  (Optional) Type of the device that holds the sensor. Set null
     *                    to use the default device.
     * @param deviceUuid  (Optional) UUID of the device that holds the sensor. Set null
     *                    to use the default device.
     * @return The new sensor ID at CommonSense, or <code>null</code> if the
     * registration failed.
     * @throws JSONException In case of invalid sensor details or if the request returned
     *                       unparseable response.
     * @throws IOException   In case of communication failure during creation of the
     *                       sensor.
     */
    public String registerSensor(String name,
                                 String displayName, String description, String dataType,
                                 String value, String deviceType, String deviceUuid)
            throws JSONException, IOException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        if (null == sSensorPrefs) {
            sSensorPrefs = context.getSharedPreferences(Sensors.PREFS_SENSORS,
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
            JSONObject dataStructJSon;
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

        // perform actual request
        Map<String, String> response = request(url, METHOD_POST, postData, cookie);

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
            JSONArray sensors = getAllSensors();
            sensors.put(sensor);
            Editor sensorEditor = sSensorPrefs.edit();
            sensorEditor.putString(Sensors.SENSOR_LIST_COMPLETE,
                    sensors.toString());
            sensorEditor.commit();
            return id;
        }

        // get device properties from preferences, so it matches the properties
        // in CommonSense
        if (null == deviceUuid) {
            deviceUuid = getDefaultDeviceUuid();
            deviceType = getDefaultDeviceType();
        }

        // add sensor to this device at CommonSense
        url = Url.SENSOR_DEVICE_URL;
        url = url.replaceFirst("%1", id);
        postData = new JSONObject();
        JSONObject device = new JSONObject();
        device.put("type", deviceType);
        device.put("uuid", deviceUuid);
        postData.put("device", device);

        response = request(url, METHOD_POST, postData, cookie);

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

        JSONArray sensors = getAllSensors();
        sensors.put(sensor);

        Editor sensorEditor = sSensorPrefs.edit();
        sensorEditor.putString(Sensors.SENSOR_LIST_COMPLETE, sensors.toString());
        sensorEditor.commit();

        Log.v(TAG, "Created sensor: '" + name + "' for device: '" + deviceType
                + "'");

        // return the new sensor ID
        return id;
    }

    /**
     * Registers a new label for this device at CommonSense.
     *
     * @param name        The name of the sensor.
     * @param description The sensor description (previously "device_type").
     * @return The new label ID at CommonSense, or <code>null</code> if the
     * registration failed.
     * @throws JSONException In case of invalid sensor details or if the request returned
     *                       unparseable response.
     * @throws IOException   In case of communication failure during creation of the
     *                       sensor.
     */
    public int registerLabel(String name, String description)
            throws JSONException, IOException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        if (null == sLabelPrefs) {
            sLabelPrefs = context.getSharedPreferences(Labels.PREFS_LABELS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

        // prepare request to create new sensor
        String url = Url.LABELS_URL;
        JSONObject postData = new JSONObject();
        JSONObject label = new JSONObject();
        label.put("name", name);
        label.put("description", description);

        postData.put("label", label);

        // perform actual request
        Map<String, String> response = request(url, METHOD_POST, postData, cookie);

        // check response code
        String code = response.get(RESPONSE_CODE);
        if (!"201".equals(code)) {
            Log.w(TAG,
                    "Failed to register label at CommonSense! Response code: "
                            + code);
            throw new IOException("Incorrect response from CommonSense: "
                    + code);
        }

        String content = response.get(RESPONSE_CONTENT);

        label = new JSONObject(content).getJSONObject("label");

        getAllLabels();

        JSONArray labels = getAllLabels();
        labels.put(label);

        Editor labelEditor = sLabelPrefs.edit();
        labelEditor.putString(Labels.LABEL_LIST_COMPLETE, labels.toString());
        labelEditor.putString(Labels.LABEL_OFFICE, label.toString());
        labelEditor.commit();

        int id = label.getInt("id");
        // return the new label ID
        return id;
    }

    /**
     * Stores all the beacons on CS in the database
     *
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public void storeAllBeacons() throws IOException, JSONException {

        JSONArray beacons = getAllSenseBeacons();
        DBHelper DB = DBHelper.getDBHelper(context);

        for (int i = 0; i < beacons.length(); i++) {
            iBeacon beacon = new iBeacon(
                    beacons.getJSONObject(i).getString("name"),
                    beacons.getJSONObject(i).getString("uuid"),
                    beacons.getJSONObject(i).getInt("major"),
                    beacons.getJSONObject(i).getInt("minor"),
                    0);
            beacon.setRemoteId(beacons.getJSONObject(i).getInt("id"));
            beacon.insertDB(DB);
        }
    }

    /**
     * requests all the beacons that are attached to the sense office label
     *
     * @return JSONArray containing JSON representation of all the beacons attached to the
     * sense office label
     * @throws IOException   occurs when the connection has trouble or an unexpected response is
     *                       obtained
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONArray getAllSenseBeacons() throws IOException, JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }
        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

        JSONObject label = new JSONObject(sAuthPrefs.getString(Labels.LABEL_OFFICE, ""));
        int labelId = label.getInt("id");

        // prepare request to create new sensor
        String url = Url.LABELS_URL + "/" + labelId + "/beacons";

        // perform actual request
        Map<String, String> response = request(url, METHOD_GET, null, cookie);

        String responseCode = response.get(RESPONSE_CODE);
        if (!"200".equals(responseCode)) {
            Log.w(TAG, "Failed to get list of beacons! Response code: "
                    + responseCode);
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);
        }

        // parse response and store the list
        JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));

        return content.getJSONArray("beacons");
    }

    /**
     * Registers a new iBeacon for this user at CommonSense.
     *
     * @param beacon the target beacon that needs to be stored
     * @return The new beacon ID at CommonSense, or <code>null</code> if the
     * registration failed.
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public int registerBeacon(iBeacon beacon)
            throws JSONException, IOException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

        // prepare request to create new sensor
        String url = Url.BEACONS_URL;
        JSONObject postData = new JSONObject();
        JSONObject jBeacon = new JSONObject();
        jBeacon.put("name", beacon.getName());
        jBeacon.put("uuid", beacon.getUUID());
        jBeacon.put("major", beacon.getMajor());
        jBeacon.put("minor", beacon.getMinor());
        postData.put("beacon", jBeacon);

        // perform actual request
        Map<String, String> response = request(url, METHOD_POST, postData, cookie);

        // check response code
        String code = response.get(RESPONSE_CODE);
        if (!"201".equals(code)) {
            Log.w(TAG,
                    "Failed to register beacon at CommonSense! Response code: "
                            + code);
            throw new IOException("Incorrect response from CommonSense: "
                    + code);
        }

        String content = response.get(RESPONSE_CONTENT);
        jBeacon = new JSONObject(content).getJSONObject("beacon");
        int id = jBeacon.getInt("id");

        JSONObject label = new JSONObject(sAuthPrefs.getString(Labels.LABEL_OFFICE, ""));
        int labelId = label.getInt("id");

        // register the created beacon.
        url = Url.BEACONS_URL + "/" + id + "/label";

        postData = new JSONObject();
        postData.put("label", new JSONObject().put("id", labelId));

        // perform actual request
        response = request(url, METHOD_POST, postData, cookie);

        // check response code
        code = response.get(RESPONSE_CODE);
        if (!"200".equals(code)) {
            Log.w(TAG,
                    "Failed to register beacon at CommonSense! Response code: "
                            + code);
            throw new IOException("Incorrect response from CommonSense: "
                    + code);
        }
        return id;
    }

    /**
     * Deletes an iBeacon for this user at CommonSense.
     *
     * @param remoteId the id of the iBeacon on CommonSense
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public void removeBeacon(int remoteId)
            throws JSONException, IOException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

        // prepare request to create new sensor
        String url = Url.BEACONS_URL + "/" + remoteId;

        // perform actual request
        Map<String, String> response = request(url, METHOD_DELETE, null, cookie);

        // check response code
        String code = response.get(RESPONSE_CODE);
        if (!"204".equals(code)) {
            Log.w(TAG,
                    "Failed to delete Beacon at CommonSense! Response code: "
                            + code);
            throw new IOException("Incorrect response from CommonSense: "
                    + code);
        }
    }

    /**
     * checks whether current user is a member of the group with id groupId.
     *
     * @param groupId id number of the specific group
     * @return true if user is a member of the group. False otherwise
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */

    public boolean isGroupMember(int groupId) throws JSONException, IOException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        boolean done = false;
        JSONArray result = new JSONArray();
        int page = 0;

        while (!done) {
            // request fresh list of groups for this device from CommonSense
            String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

            String url = Url.GROUP_URL + "?page=" + page + "&per_page=" + GroupPrefs.PAGE_SIZE;
            Map<String, String> response = request(url, METHOD_GET, null, cookie);

            String responseCode = response.get(RESPONSE_CODE);
            if (!"200".equals(responseCode)) {
                Log.w(TAG, "Failed to get list of sensors! Response code: "
                        + responseCode);
                throw new IOException("Incorrect response from CommonSense: "
                        + responseCode);
            }

            // parse response and store the list
            JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));
            JSONArray groupList = content.getJSONArray("groups");

            // put the group list in the result array
            for (int i = 0; i < groupList.length(); i++) {
                if (groupList.getJSONObject(i).getInt("id") == groupId) {
                    return true;
                }
            }

            if (groupList.length() < Sensors.PAGE_SIZE) {
                // all groups received
                done = true;
            } else {
                // get the next page
                page++;
            }
        }
        return false;
    }

    /**
     * adds the current user to a group with id groupId
     *
     * @param groupId id number of the specific group
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public void joinGroup(int groupId) throws JSONException, IOException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
        String url = Url.GROUP_URL + "/" + groupId + "/users";

        String beaconSensorString = sAuthPrefs.getString(Sensors.BEACON_SENSOR,
                null);
        JSONObject beaconSensor = new JSONObject(beaconSensorString);
        int sensorId = (int) beaconSensor.getLong("id");

        JSONObject user = new JSONObject();
        user.put(
                "user",
                new JSONObject().put("id", sAuthPrefs.getInt(Auth.USER_ID, -1))
                        .put("username",
                                sAuthPrefs.getString(Auth.PREFS_CREDS_UNAME,
                                        null)));

        JSONArray sensors = new JSONArray().put(sensorId);
        user.put("sensors", sensors);

        JSONObject displayInfo = new JSONObject();
        displayInfo.put("show_username", 1);
        displayInfo.put("show_id", 1);
        user.put("display_user_info", displayInfo);

        JSONObject permissions = new JSONObject();
        permissions.put("add_sensors", 1);
        permissions.put("list_sensors", 1);
        permissions.put("list_users", 1);
        permissions.put("remove_sensors", 0);
        permissions.put("edit_group", 0);
        permissions.put("add_users", 0);
        user.put("group_permissions", permissions);

        user.put("access_password", GroupPrefs.GROUP_PASSWORD);

        JSONObject postData = new JSONObject();
        postData.put("users", new JSONArray().put(user));

        Map<String, String> response = request(url, METHOD_POST, postData, cookie);

        // check response code
        String code = response.get(RESPONSE_CODE);

        if ("403".equalsIgnoreCase(code)) {
            Log.w(TAG,
                    "CommonSense authentication while joining group Response: forbidden!");
            throw new IOException("Incorrect response from CommonSense: "
                    + code);
        }
        if (!"201".equals(code)) {
            Log.w(TAG, "Error: " + code);
            throw new IOException("Incorrect response from CommonSense: "
                    + code);
        }
    }

    /**
     * login using the last used credentials.
     *
     * @return 0 if login succeeded, -1 if an exception occured and -2 if the
     * authentication failed (wrong credentials)
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
     * login using the credits provided. Also stores session coockie in the SharedPreferences
     * @param username
     * @param password
     * @return 0 if login succeeded, -1 if an exception occured and -2 if the
     * authentication failed (wrong credentials)
     */
    public int login(String username, String password) {

        Map<String, String> response;
        // params comes from the execute() call: params[0] is the url.
        final JSONObject user = new JSONObject();
        try {
            user.put("username", username);
            user.put("password", Hash.hashMD5(password));
            response = request(Url.LOGIN_URL, METHOD_POST, user, null);

        } catch (Exception e) {
            Log.w(TAG, "Exception during login! " + e + ": '" + e.getMessage());
            // handle result below
            return -1;
        }

        // if response code is not 200 (OK), the login was incorrect
        String responseCode = response.get(RESPONSE_CODE);
        int result;
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
     * @throws IOException   In case of communication failure to CommonSense
     */
    public int logout() throws IOException {

        Map<String, String> response;
        // params comes from the execute() call: params[0] is the url.

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
        response = request(Url.LOGOUT_URL, METHOD_GET, null, cookie);

        // if response code is not 200 (OK), the login was incorrect
        String responseCode = response.get(RESPONSE_CODE);
        int result;
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
     * Downloads the user/account information from CommonSense
     *
     * @return JSONObject containing all available user information on CommnonSense
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONObject getUserInfo() throws IOException, JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
        String url = Url.CURRENT_USER_URL;

        Map<String, String> response = request(url, METHOD_GET, null, cookie);
        String responseCode = response.get(RESPONSE_CODE);

        if ("403".equalsIgnoreCase(responseCode)) {
            Log.w(TAG,
                    "CommonSense authentication while downloading user info Response: forbidden!");
            throw new IOException("Incorrect response from CommonSense: " + responseCode);
        }

        if (!"200".equals(responseCode)) {
            Log.w(TAG,
                    "Incorrect response from CommonSense: " + responseCode);
            throw new IOException("Incorrect response from CommonSense: " + responseCode);
        }
        Log.w(TAG, "Download successful: " + responseCode);

        // parse response and store the list
        JSONObject content = new JSONObject(response.get(RESPONSE_CONTENT));
        Log.v(TAG, content.toString(1));
        JSONObject user = content.getJSONObject("user");

        Editor sAuthEditor = sAuthPrefs.edit();
        sAuthEditor.putInt(Auth.USER_ID, user.getInt("id"));
        sAuthEditor.commit();

        return user;
    }

    /**
     * Saves batch of beacon measurements to the Beacon sensor in CommonSense
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public void sendBeaconData(JSONObject dataPackage) throws JSONException,
            IOException {

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

        Map<String, String> response = request(url, METHOD_POST, dataPackage,
                cookie);

        // check response code
        String code = response.get(RESPONSE_CODE);

        if ("403".equalsIgnoreCase(code)) {
            Log.w(TAG,
                    "CommonSense authentication while downloading user info Response: forbidden!");
            throw new IOException("Incorrect response from CommonSense: " + code);
        }
        if (!"201".equals(code)) {
            Log.w(TAG,
                    "Incorrect response from CommonSense: " + code);
            throw new IOException("Incorrect response from CommonSense: " + code);
        }
        Log.w(TAG, "Upload successful: " + code);
    }

    /**
     * Downloads the latest Beacon Sensor measurement from CommonSense
     *
     * @return JSON object of the last Beacon Sensor measurement
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONObject getTotalTime() throws IOException, JSONException {

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

        Map<String, String> response = request(url, METHOD_GET, null, cookie);
        String responseCode = response.get(RESPONSE_CODE);

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
        } else {
            Log.w(TAG, "responsecode: " + responseCode);
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);
        }
    }

    /**
     * Downloads the latest measurement of the Beacon Sensor before a certain timestamp
     * @param timeStamp unix timestamp in seconds
     * @return JSONObject containing the latest measurement of the Beacon Sensor before timestamp.
     * If there is no such measurement on CS returns null.
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONObject getStatusBefore(long timeStamp) throws IOException,
            JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
        String beaconSensorString = sAuthPrefs.getString(Sensors.BEACON_SENSOR,
                null);

        JSONObject beaconSensor = new JSONObject(beaconSensorString);

        int sensorId = (int) beaconSensor.getLong("id");
        String url = Url.SENSORS_URL + "/" + sensorId + "/data" + "?end_date="
                + timeStamp + "&last=true";

        Map<String, String> response = request(url, METHOD_GET, null, cookie);
        String responseCode = response.get(RESPONSE_CODE);

        if ("403".equalsIgnoreCase(responseCode)) {
            Log.w(TAG,
                    "CommonSense authentication while downloading data Response: forbidden!");
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);

        }
        if ("200".equals(responseCode)) {
            Log.w(TAG, "Download successful: " + responseCode);

            JSONObject data = new JSONObject(response.get(RESPONSE_CONTENT));
            if(data.getJSONArray("data").length() == 0){
                return null;
            }
            return data.getJSONArray("data").getJSONObject(0);
        } else {
            Log.w(TAG, "responsecode: " + responseCode);
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);
        }
    }

    /**
     * Downloads the first measurement of the Beacon Sensor after a certain timestamp
     * @param timeStamp unix timestamp in seconds
     * @return JSONObject containing the first measurement of the Beacon Sensor after timestamp.
     * If there is no such measurement on CS returns null.
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONObject getStatusAfter(long timeStamp) throws IOException,
            JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);
        String beaconSensorString = sAuthPrefs.getString(Sensors.BEACON_SENSOR,
                null);

        JSONObject beaconSensor = new JSONObject(beaconSensorString);

        int sensorId = (int) beaconSensor.getLong("id");
        String url = Url.SENSORS_URL + "/" + sensorId + "/data"
                + "?start_date=" + timeStamp + "&page=0&per_page";

        Map<String, String> response = request(url, METHOD_GET, null, cookie);
        String responseCode = response.get(RESPONSE_CODE);

        if ("403".equalsIgnoreCase(responseCode)) {
            Log.w(TAG,
                    "CommonSense authentication while downloading data Response: forbidden!");
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);
        }
        if ("200".equals(responseCode)) {
            Log.w(TAG, "Download successful: " + responseCode);

            JSONObject data = new JSONObject(response.get(RESPONSE_CONTENT));
            if(data.getJSONArray("data").length() == 0){
                return null;
            }
            return data.getJSONArray("data").getJSONObject(0);
        } else {
            Log.w(TAG, "responsecode: " + responseCode);
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);
        }
    }

    /**
     * Downloads the latest measurement of the Group sensor. This a group overview of every
     * members latest status.
     * @return JSONObject containing the latest group overview
     * @throws IOException   In case of communication failure to CommonSense
     * @throws JSONException In case of unparseable response from CommonSense
     */
    public JSONObject getGroupResult() throws IOException, JSONException {

        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }

        String cookie = sAuthPrefs.getString(Auth.LOGIN_COOKIE, null);

        String url = Url.SENSORS_URL + "/" + GroupPrefs.GROUP_SENSOR_ID
                + "/data" + "?last=true";

        Map<String, String> response = request(url, METHOD_GET, null, cookie);
        String responseCode = response.get(RESPONSE_CODE);

        if ("403".equalsIgnoreCase(responseCode)) {
            Log.w(TAG,
                    "CommonSense authentication while downloading data Response: forbidden!");
        }
        if ("200".equals(responseCode)) {
            Log.w(TAG, "Download successful: " + responseCode);

            return new JSONObject(response.get(RESPONSE_CONTENT));
        } else {
            Log.w(TAG, "responsecode: " + responseCode);
            throw new IOException("Incorrect response from CommonSense: "
                    + responseCode);
        }
    }

    /**
     * Performs request at CommonSense API. Returns the response code, content,
     * and headers.
     *
     * @param urlString Complete URL to perform request to.
     * @param content   (Optional) Content for the request.
     * @param method    http method (GET, POST, PUT, DELETE, UPDATE etc)
     * @param cookie    (Optional) Cookie header for the request.
     * @return Map with SenseApi.KEY_CONTENT and SenseApi.KEY_RESPONSE_CODE
     * fields, plus fields for all response headers.
     * @throws IOException   In case of communication failure to CommonSense
     */
    public Map<String, String> request(String urlString, String method,
                                       JSONObject content, String cookie) throws IOException {

        HttpURLConnection urlConnection = null;
        HashMap<String, String> result = new HashMap<>();
        try {

            boolean compress = false;

            // open new URL connection channel.
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();

            //TODO add https option when live server comes available

            // some parameters
            urlConnection.setUseCaches(false);
            urlConnection.setInstanceFollowRedirects(false);
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestMethod(method);

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
     * @return The default device UUID, e.g. the phone's IMEI String
     */
    @TargetApi(9)
    public String getDefaultDeviceUuid() {
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
     * @return The default device type, i.e. the phone's model String
     */
    public String getDefaultDeviceType() {
        if (null == sAuthPrefs) {
            sAuthPrefs = context.getSharedPreferences(Auth.PREFS_CREDS,
                    Context.MODE_PRIVATE);
        }
        return sAuthPrefs.getString(Auth.DEVICE_TYPE, Build.MODEL);
    }
}