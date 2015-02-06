package nl.senseos.mytimeatsense.sync;

import java.io.IOException;
import java.util.GregorianCalendar;

import nl.senseos.mytimeatsense.commonsense.CommonSenseAdapter;
import nl.senseos.mytimeatsense.commonsense.MsgHandler;
import nl.senseos.mytimeatsense.util.DemanesConstants.Auth;
import nl.senseos.mytimeatsense.storage.DBHelper;
import nl.senseos.mytimeatsense.util.DemanesConstants.StatusPrefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.os.Handler;
import android.util.Log;

public class GlobalUpdateService extends IntentService {

	private static final String TAG = GlobalUpdateService.class.getSimpleName();
	private CommonSenseAdapter cs;
	private SharedPreferences authPrefs;
	private SharedPreferences statusPrefs;
	private DBHelper DB;

	private GregorianCalendar calMidnight;
	private GregorianCalendar calMondayMidnight;

	public GlobalUpdateService() {
		super(TAG);
	}

	protected void onHandleIntent(Intent intent) {

		Log.v(TAG, "global update");
		
		DB = DBHelper.getDBHelper(this);
		
		authPrefs = getSharedPreferences(Auth.PREFS_CREDS, Context.MODE_PRIVATE);
		statusPrefs = getSharedPreferences(StatusPrefs.PREFS_STATUS,
				Context.MODE_PRIVATE);
		
		cs = new CommonSenseAdapter(this);
		
		Handler updateHandler = new Handler(MsgHandler.getInstance().getLooper());
		updateHandler.post(new GlobalUpdateRunnable());
	}
	
	private class GlobalUpdateRunnable implements Runnable{

		@Override
		public void run() {
			String mEmail = authPrefs.getString(Auth.PREFS_CREDS_UNAME, null);
			String mPassword = authPrefs.getString(Auth.PREFS_CREDS_PASSWORD, null);

			if (mEmail == null || mPassword == null) {
				Log.v(TAG, "no creds, return");
				return;
			}

			// login to commonsense
			Log.v(TAG, "try to login...");
			int loginSuccess = cs.login();
			Log.v(TAG, "login success: " + loginSuccess);

			// login returns 0 if successful.
			if (loginSuccess != 0) {
				return;
			}

			// check if sensor is present
			boolean sensorPresent = false;
			try {
				sensorPresent = cs.hasBeaconSensor();
				// if not present, make one!
				if (!sensorPresent) {
					sensorPresent = cs.registerBeaconSensor();
				}
			} catch (IOException | JSONException e1) {
				e1.printStackTrace();
			}

			if (sensorPresent) {
				Log.v(TAG, "full CS sync");
				fullSyncCS();
			}

			// logout
			try {
				cs.logout();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
	}

	private void fullSyncCS() {

		try {
			// first get the latest update from CS
			JSONObject response = cs.fetchTotalTime();

			boolean localStatus = statusPrefs.getBoolean(
					StatusPrefs.STATUS_IN_OFFICE, false);

			long localTs = statusPrefs.getLong(StatusPrefs.STATUS_TIMESTAMP, 0);

			JSONObject dataPackage;

			// no data points present on CS, upload local value as is
			if (response == null) {
				dataPackage = dbToJSON(false, 0, 0);
			} else {
				// obtain latest update from response
				long lastUpdateTs = response.getLong("date");
				JSONObject value = new JSONObject(response.getString("value"));
				boolean lastUpdateStatus = value.getBoolean("status");
				long lastUpdateTotalTime = value.getLong("total_time");

				dataPackage = dbToJSON(lastUpdateStatus, lastUpdateTs,
						lastUpdateTotalTime);
			}
			// update and upload to CS
			
			int res = cs.sendBeaconData(dataPackage);
			if (res == 0) {
				DB.deleteAllRows(DBHelper.DetectionLog.TABLE_NAME);
			}

			// fetch latest status and update the status in SharedPreferences
			response = cs.fetchTotalTime();
			if (response == null) {
				return;
			}
			JSONObject valueCurrent = response;

			calMidnight = new GregorianCalendar();
			calMidnight.set(GregorianCalendar.HOUR_OF_DAY, 0);
			calMidnight.set(GregorianCalendar.MINUTE, 0);
			calMidnight.set(GregorianCalendar.SECOND, 0);

			response = cs
					.fetchStatusBefore(calMidnight.getTimeInMillis() / 1000);
			JSONObject valueBeforeMidnight = response;

			response = cs
					.fetchStatusAfter(calMidnight.getTimeInMillis() / 1000);
			JSONObject valueAfterMidnight = response;

			calMondayMidnight = new GregorianCalendar();
			calMondayMidnight.set(GregorianCalendar.DAY_OF_WEEK,
					GregorianCalendar.MONDAY);
			calMondayMidnight.set(GregorianCalendar.HOUR_OF_DAY, 0);
			calMondayMidnight.set(GregorianCalendar.MINUTE, 0);
			calMondayMidnight.set(GregorianCalendar.SECOND, 0);
			
			response = cs
					.fetchStatusBefore(calMondayMidnight.getTimeInMillis() / 1000);
			JSONObject valueBeforeMondayMidnight = response;

			response = cs
					.fetchStatusAfter(calMondayMidnight.getTimeInMillis() / 1000);
			JSONObject valueAfterMondayMidnight = response;

			updateStatusPrefs(valueCurrent, valueBeforeMidnight,
					valueAfterMidnight, valueBeforeMondayMidnight,
					valueAfterMondayMidnight);

		} catch (JSONException | IOException e) {
			e.printStackTrace();
		}
	}

	private void updateStatusPrefs(JSONObject valueCurrent,
			JSONObject valueBeforeMidnight, JSONObject valueAfterMidnight,
			JSONObject valueBeforeMondayMidnight,
			JSONObject valueAfterMondayMidnight) {

		try {
			Editor statusEditor = statusPrefs.edit();

			long totalTime = new JSONObject(valueCurrent.getString("value"))
					.getLong("total_time");
			boolean statusCurrent = new JSONObject(
					valueCurrent.getString("value")).getBoolean("status");
			long timeStampCurrent = valueCurrent.getLong("date");

			statusEditor.putLong(StatusPrefs.STATUS_TOTAL_TIME, totalTime);
			statusEditor.putBoolean(StatusPrefs.STATUS_IN_OFFICE, statusCurrent);
			statusEditor.putLong(StatusPrefs.STATUS_TIMESTAMP, timeStampCurrent);

			long todayMidnight;

			if (valueBeforeMidnight.getJSONArray("data").length() == 0) {
				todayMidnight = new JSONObject(valueAfterMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
			} else if (valueAfterMidnight.getJSONArray("data").length() == 0) {
				todayMidnight = new JSONObject(valueBeforeMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
			} else {
				long beforeMidnight = new JSONObject(valueBeforeMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
				boolean beforeMidnightStatus = new JSONObject(
						valueBeforeMidnight.getJSONArray("data")
								.getJSONObject(0).getString("value"))
						.getBoolean("status");
				long beforeMidnightTS = valueBeforeMidnight
						.getJSONArray("data").getJSONObject(0).getLong("date");
				long afterMidnight = new JSONObject(valueAfterMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
				boolean afterMidnightStatus = new JSONObject(
						valueBeforeMidnight.getJSONArray("data")
								.getJSONObject(0).getString("value"))
						.getBoolean("status");
				long afterMidnightTS = valueAfterMidnight.getJSONArray("data")
						.getJSONObject(0).getLong("date");

				if ((!beforeMidnightStatus && !afterMidnightStatus)
						|| (afterMidnightTS - beforeMidnightTS > LocalUpdateService.TIME_OUT_LIMIT)) {
					todayMidnight = beforeMidnight;
				} else if (beforeMidnightStatus && !afterMidnightStatus) {
					todayMidnight = afterMidnight;
				} else if (!beforeMidnightStatus && afterMidnightStatus) {
					todayMidnight = afterMidnight;
				} else {
					todayMidnight = afterMidnight
							+ (beforeMidnightTS - (calMidnight
									.getTimeInMillis() / 1000));
				}
			}
			statusEditor.putLong(StatusPrefs.STATUS_TIME_TODAY, totalTime
					- todayMidnight);

			long mondayMidnight;

			if (valueBeforeMondayMidnight.getJSONArray("data").length() == 0) {
				
				mondayMidnight = new JSONObject(valueAfterMondayMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
			} else if (valueAfterMondayMidnight.getJSONArray("data").length() == 0) {

				mondayMidnight = new JSONObject(valueBeforeMondayMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
			} else {

				long beforeMidnight = new JSONObject(valueBeforeMondayMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
				boolean beforeMidnightStatus = new JSONObject(
						valueBeforeMondayMidnight.getJSONArray("data")
								.getJSONObject(0).getString("value"))
						.getBoolean("status");
				long beforeMidnightTS = valueBeforeMondayMidnight
						.getJSONArray("data").getJSONObject(0).getLong("date");
				long afterMidnight = new JSONObject(valueAfterMondayMidnight
						.getJSONArray("data").getJSONObject(0)
						.getString("value")).getLong("total_time");
				long afterMidnightTS = valueAfterMondayMidnight
						.getJSONArray("data").getJSONObject(0).getLong("date");
				boolean afterMidnightStatus = new JSONObject(
						valueBeforeMondayMidnight.getJSONArray("data")
								.getJSONObject(0).getString("value"))
						.getBoolean("status");

				if ((!beforeMidnightStatus && !afterMidnightStatus)
						|| (afterMidnightTS - beforeMidnightTS > LocalUpdateService.TIME_OUT_LIMIT)) {
					mondayMidnight = beforeMidnight;
				} else if (beforeMidnightStatus && !afterMidnightStatus) {
					mondayMidnight = afterMidnight;
				} else if (!beforeMidnightStatus && afterMidnightStatus) {
					mondayMidnight = afterMidnight;
				} else {
					mondayMidnight = beforeMidnight
							+ (afterMidnightTS - (calMondayMidnight
									.getTimeInMillis() / 1000));
				}

				
			}
			
			statusEditor.putLong(StatusPrefs.STATUS_TIME_WEEK, totalTime
					- mondayMidnight);

			statusEditor.commit();

		} catch (JSONException e) {
			e.printStackTrace();
		}
	}

	public JSONObject dbToJSON(boolean initStatus, long lastUpdateTs,
			long lastUpdateTotal) throws JSONException {

		JSONObject res = new JSONObject();
		JSONArray dataArray = new JSONArray();
		JSONObject dataToken;

		long total = lastUpdateTotal;

		boolean follower = initStatus;
		long followerTs = lastUpdateTs;

		boolean leader;
		long leaderTs = 0;
		
		Cursor log = DB.getCompleteLog();
		
		log.moveToFirst();

		while (log.getPosition() < log.getCount()) {

			dataToken = new JSONObject();

			leader = log.getInt(2) > 0;
			leaderTs = log.getLong(1);

			if (leaderTs - followerTs > LocalUpdateService.TIME_OUT_LIMIT
					|| (!leader && !follower)) {
				Log.e(TAG,"time out "+leaderTs+","+followerTs);
				dataToken.put("value", "{\"total_time\":" + total + ","
						+ "\"status\":" + Boolean.toString(leader) + "}");
			} else if ((!leader && follower) ||(leader && !follower) ) {

				long delta = (1 / 2) * (leaderTs - followerTs);
				total = total + delta;
				dataToken.put("value", "{\"total_time\":" + total + ","
						+ "\"status\":" + Boolean.toString(leader) + "}");
			} else {
				long delta = (leaderTs - followerTs);
				total = total + delta;
				dataToken.put("value", "{\"total_time\":" + total + ","
						+ "\"status\":" + Boolean.toString(leader) + "}");
			}
			dataToken.put("date", leaderTs);
			dataArray.put(dataToken);

			follower = leader;
			followerTs = leaderTs;

			log.moveToNext();
		}

		res.put("data", dataArray);
		return res;
	}
}
