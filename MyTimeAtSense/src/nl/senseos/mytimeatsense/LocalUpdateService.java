package nl.senseos.mytimeatsense;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import nl.senseos.mytimeatsense.DemanesConstants.Prefs;
import android.app.IntentService;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.util.Log;

public class LocalUpdateService extends IntentService {

	private final static String TAG = IntentService.class.getSimpleName();
	private DBHelper DB;

	public LocalUpdateService() {
		super(TAG);
	}

	/**
	 * Update the shared preferences, database and commonsense with result ble
	 * scan.
	 */
	@Override
	protected void onHandleIntent(Intent intent) {

		// retreive latest scan result
		boolean detected = intent.getBooleanExtra(
				BluetoothLeScanService.SCAN_RESULT, false);
		long tsCurrent = intent.getLongExtra(
				BluetoothLeScanService.SCAN_RESULT_TIMESTAMP, 0);

		Log.d(TAG, "detected: " + detected + " ts: " + tsCurrent);

		// insert data point in database
		DB = DBHelper.getDBHelper(this);
		ContentValues v = new ContentValues();
		v.put(DBHelper.DetectionLog.COLUMN_TIMESTAMP, tsCurrent);
		v.put(DBHelper.DetectionLog.COLUMN_DETECTION_RESULT, detected);
		DB.insertOrIgnore(DBHelper.DetectionLog.TABLE_NAME, v);

		// update current state in shared preferences
		SharedPreferences statusPrefs = getSharedPreferences(
				Prefs.PREFS_STATUS, Context.MODE_PRIVATE);
		Editor prefsEditor = statusPrefs.edit();
		
		prefsEditor.putBoolean(Prefs.STATUS_IN_OFFICE, detected);
		prefsEditor.putLong(Prefs.STATUS_TIMESTAMP, tsCurrent);

		Date dCurrent = new Date(tsCurrent * 1000);
		GregorianCalendar cCurrent = new GregorianCalendar();
		cCurrent.setTime(dCurrent);

		Date dPrevious = new Date(
				statusPrefs.getLong(Prefs.STATUS_TIMESTAMP, 0) * 1000);
		GregorianCalendar cPrevious = new GregorianCalendar();
		cPrevious.setTime(dPrevious);

		if (statusPrefs.getBoolean(Prefs.STATUS_IN_OFFICE, false) && detected) {

			prefsEditor.putLong(Prefs.STATUS_TOTAL_TIME,
					statusPrefs.getLong(Prefs.STATUS_TOTAL_TIME, 0) + tsCurrent
							- statusPrefs.getLong(Prefs.STATUS_TIMESTAMP, 0));

			if (cCurrent.get(Calendar.DAY_OF_YEAR) > cPrevious
					.get(Calendar.DAY_OF_YEAR)) {

				GregorianCalendar c = new GregorianCalendar(
						cCurrent.get(Calendar.YEAR),
						cCurrent.get(Calendar.MONTH),
						cCurrent.get(Calendar.DAY_OF_MONTH), 0, 0);

				prefsEditor.putLong(Prefs.STATUS_TIME_TODAY, tsCurrent
						- c.getTime().getTime() / 1000);

			} else {

				prefsEditor.putLong(
						Prefs.STATUS_TIME_TODAY,
						statusPrefs.getLong(Prefs.STATUS_TIME_TODAY, 0)
								+ tsCurrent
								- statusPrefs
										.getLong(Prefs.STATUS_TIMESTAMP, 0));
			}

			if (cCurrent.get(Calendar.WEEK_OF_YEAR) > cPrevious
					.get(Calendar.WEEK_OF_YEAR)) {

				GregorianCalendar c = new GregorianCalendar(
						cCurrent.get(Calendar.YEAR),
						cCurrent.get(Calendar.MONTH),
						cCurrent.get(Calendar.DAY_OF_MONTH), 0, 0);
				c.set(Calendar.DAY_OF_WEEK, 0);

				prefsEditor.putLong(Prefs.STATUS_TIME_WEEK, tsCurrent
						- c.getTime().getTime() / 1000);

			} else {
				prefsEditor.putLong(
						Prefs.STATUS_TIME_WEEK,
						statusPrefs.getLong(Prefs.STATUS_TIME_WEEK, 0)
								+ tsCurrent
								- statusPrefs
										.getLong(Prefs.STATUS_TIMESTAMP, 0));
			}

		} else {
			if (cCurrent.get(Calendar.DAY_OF_YEAR) > cPrevious
					.get(Calendar.DAY_OF_YEAR)) {

				prefsEditor.putLong(Prefs.STATUS_TIME_TODAY, 0);
			}
			if (cCurrent.get(Calendar.WEEK_OF_YEAR) > cPrevious
					.get(Calendar.WEEK_OF_YEAR)) {

				prefsEditor.putLong(Prefs.STATUS_TIME_WEEK, 0);
			}
		}
		prefsEditor.commit();
	}
}
