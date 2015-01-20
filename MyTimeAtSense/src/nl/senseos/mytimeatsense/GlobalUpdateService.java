package nl.senseos.mytimeatsense;

import java.io.IOException;

import nl.senseos.mytimeatsense.CommonSenseConstants.Auth;
import nl.senseos.mytimeatsense.DemanesConstants.Prefs;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.util.Log;

public class GlobalUpdateService extends IntentService {

	private static final String TAG = GlobalUpdateService.class.getSimpleName();
	private CommonSenseAdapter cs;
	private SharedPreferences sAuthPrefs;
	private DBHelper DB;

	public GlobalUpdateService() {
		super(TAG);
	}

	protected void onHandleIntent(Intent intent) {

		Log.v(TAG, "global update");
		sAuthPrefs = getSharedPreferences(Auth.PREFS_CREDS, 0);
		String mEmail = sAuthPrefs.getString(Auth.PREFS_CREDS_UNAME, null);
		String mPassword = sAuthPrefs
				.getString(Auth.PREFS_CREDS_PASSWORD, null);

		if (mEmail == null || mPassword == null) {
			Log.v(TAG, "no creds, return");
			return;
		}

		// login to commonsense
		Log.v(TAG, "try to login...");
		cs = new CommonSenseAdapter(this);
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

		// no sensor means no updating, continue to logout
		if (sensorPresent) {

			DB = DBHelper.getDBHelper(this);
			Cursor log = DB.getCompleteLog();

			if (log.getCount() < 2) {
				Log.v(TAG,
						"not enough datapoints in the db, update local status from CS");
				updateLocalStatus();
				return;
			} else {
				Log.v(TAG, "enough datapoints in the db, full CS sync");
				fullSyncCS();
				return;
			}
		}

		// logout
		try {
			cs.logout();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void updateLocalStatus() {

		SharedPreferences statusPrefs = getSharedPreferences(
				Prefs.PREFS_STATUS, Context.MODE_PRIVATE);

		Editor statusEditor = statusPrefs.edit();
		try {
			// fetch latest status and update the status in SharedPreferences
			JSONObject response = cs.fetchTotalTime();
			if (response == null) {
				return;
			}
			JSONObject value = new JSONObject(response.getString("value"));

			long totalTime = value.getLong("total_time");
			statusEditor.putLong(Prefs.STATUS_TOTAL_TIME, totalTime);
			statusEditor.putBoolean(Prefs.STATUS_IN_OFFICE,
					value.getBoolean("status"));
			statusEditor.putLong(Prefs.STATUS_TIMESTAMP,
					System.currentTimeMillis() / 1000);

			response = cs.fetchTimeToday();
			if (response == null) {
				return;
			}
			value = new JSONObject(response.getString("value"));

			statusEditor.putLong(Prefs.STATUS_TIME_TODAY,
					totalTime - value.getLong("total_time"));
			response = cs.fetchTimeThisWeek();
			if (response == null) {
				return;
			}
			value = new JSONObject(response.getString("value"));

			statusEditor.putLong(Prefs.STATUS_TIME_WEEK,
					totalTime - value.getLong("total_time"));

			statusEditor.commit();

		} catch (JSONException | IOException e) {
			e.printStackTrace();
		}
	}

	private void fullSyncCS() {

		try {
			// first get the latest update from CS
			JSONObject response = cs.fetchTotalTime();

			SharedPreferences statusPrefs = getSharedPreferences(
					Prefs.PREFS_STATUS, Context.MODE_PRIVATE);

			boolean localStatus = statusPrefs.getBoolean(
					Prefs.STATUS_IN_OFFICE, false);
			long localTs = statusPrefs.getLong(Prefs.STATUS_TIMESTAMP, 0);

			long newTotalTime = 0;

			// no data points present on CS, upload local value as is
			if (response == null) {
				newTotalTime = computeIncrement(false, 0);
			} else {
				// obtain latest update from response
				long lastUpdateTs = response.getLong("date");
				JSONObject value = new JSONObject(response.getString("value"));
				boolean lastUpdateStatus = value.getBoolean("status");
				long lastUpdateTotalTime = value.getLong("total_time");

				// compute new total time
				newTotalTime = lastUpdateTotalTime
						+ computeIncrement(lastUpdateStatus, lastUpdateTs);
			}
			// update and upload to CS
			int res = cs.sendBeaconData(localTs, newTotalTime, localStatus);
			if (res == 0) {
				DB.deleteAllRows(DBHelper.DetectionLog.TABLE_NAME);
			}

			Editor statusEditor = statusPrefs.edit();

			// fetch latest status and update the status in SharedPreferences
			response = cs.fetchTotalTime();
			if (response == null) {
				return;
			}
			JSONObject value = new JSONObject(response.getString("value"));

			long totalTime = value.getLong("total_time");
			statusEditor.putLong(Prefs.STATUS_TOTAL_TIME, totalTime);
			statusEditor.putBoolean(Prefs.STATUS_IN_OFFICE,
					value.getBoolean("status"));
			statusEditor.putLong(Prefs.STATUS_TIMESTAMP,
					System.currentTimeMillis() / 1000);

			response = cs.fetchTimeToday();
			if (response == null) {
				return;
			}
			value = new JSONObject(response.getString("value"));

			statusEditor.putLong(Prefs.STATUS_TIME_TODAY,
					totalTime - value.getLong("total_time"));
			response = cs.fetchTimeThisWeek();
			if (response == null) {
				return;
			}
			value = new JSONObject(response.getString("value"));

			statusEditor.putLong(Prefs.STATUS_TIME_WEEK,
					totalTime - value.getLong("total_time"));

			statusEditor.commit();

		} catch (JSONException | IOException e) {
			e.printStackTrace();
		}
	}

	public long computeIncrement(boolean initStatus, long lastUpdateTs) {

		Cursor log = DB.getCompleteLog();

		boolean follower = initStatus;
		boolean leader;
		long leaderTs = 0;
		long followerTs = lastUpdateTs;

		long res = 0;
		log.moveToFirst();

		if (log.getLong(1) - lastUpdateTs > 4000) {

			follower = false;
		}

		log.moveToFirst();
		while (log.getPosition() < log.getCount()) {

			leader = log.getInt(2) > 0;
			leaderTs = log.getLong(1);

			if (leader && follower) {

				res = res + (leaderTs - followerTs);
			}
			follower = leader;
			followerTs = leaderTs;
			log.moveToNext();
		}
		return res;
	}
}
