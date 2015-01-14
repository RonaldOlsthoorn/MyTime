package nl.senseos.mytimeatsense;

import java.io.IOException;

import org.json.JSONException;

import nl.senseos.mytimeatsense.R;
import nl.senseos.mytimeatsense.CommonSenseConstants.Auth;
import nl.senseos.mytimeatsense.CommonSenseConstants.Sensors;
import nl.senseos.mytimeatsense.DemanesConstants.Prefs;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class PersonalOverviewActivity extends Activity {

	private String TAG = "perosnalOverviewActivity";
	private final int REQUEST_ENABLE_BT = 10;
	private final int REQUEST_CREDENTIALS = 20;

	private TextView today;
	private TextView thisWeek;
	private TextView thisLife;
	private TextView status;
	private View mProgressView;
	private View mContentView;
	private long REPEAT_INTEVAL_MINS_BLE = 5;
	private long REPEAT_INTEVAL_MINS_UPLOAD = 45;

	private AlarmManager alarmMgr;	
	private SharedPreferences statusPrefs;

	private SharedPreferences sAuthPrefs;
	private String mUsername;
	private String mPassword;
	private Handler timerHandler = new Handler();
	
	private Intent BleServiceIntent;
	private Intent GlobalUpdateServiceIntent;
	private PendingIntent GlobalUpdatePendingIntent;
	private PendingIntent BlePendingIntent;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_personal_overview);
		
		// check if password/username is set
		if (null == sAuthPrefs) {
			sAuthPrefs = getSharedPreferences(Auth.PREFS_CREDS,
					Context.MODE_PRIVATE);
		}
		

		mUsername = sAuthPrefs.getString(Auth.PREFS_CREDS_UNAME, null);
		mPassword = sAuthPrefs.getString(Auth.PREFS_CREDS_PASSWORD, null);

		if (mUsername == null || mPassword == null) {
			Intent requestCredsIntent = new Intent(this, LoginActivity.class);
			startActivityForResult(requestCredsIntent, REQUEST_CREDENTIALS);
			return;
		}

		// Use this check to determine whether BLE is supported on the device.
		// Then you can
		// selectively disable BLE-related features.
		if (!getPackageManager().hasSystemFeature(
				PackageManager.FEATURE_BLUETOOTH_LE)) {
			Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_LONG)
					.show();
			finish();
		}
		// Initializes a Bluetooth adapter. For API level 18 and above, get a
		// reference to
		// BluetoothAdapter through BluetoothManager.
		final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothAdapter mBluetoothAdapter = bluetoothManager.getAdapter();

		// Checks if Bluetooth is supported on the device.
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_LONG)
					.show();
			finish();
			return;
		}

		// Ensures Bluetooth is available on the device and it is enabled. If
		// not,
		// displays a dialog requesting user permission to enable Bluetooth.
		if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
		}

		statusPrefs = getSharedPreferences(Prefs.PREFS_STATUS,
				Context.MODE_PRIVATE);

		today = (TextView) findViewById(R.personal_overview.today);
		thisWeek = (TextView) findViewById(R.personal_overview.this_week);
		thisLife = (TextView) findViewById(R.personal_overview.this_life);
		status = (TextView) findViewById(R.personal_overview.status);

		mProgressView = findViewById(R.personal_overview.fetch_progress);
		mContentView = findViewById(R.personal_overview.content);

		BleServiceIntent = new Intent(this, BleAlarmReceiver.class);
		BlePendingIntent = PendingIntent.getBroadcast(this, 2, BleServiceIntent, 0);

		alarmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + (1 * 1000),
				REPEAT_INTEVAL_MINS_BLE * 60 * 1000, BlePendingIntent);

		GlobalUpdateServiceIntent = new Intent(this, GlobalUpdateAlarmReceiver.class);
		GlobalUpdatePendingIntent = PendingIntent.getBroadcast(this, 2, GlobalUpdateServiceIntent,
				0);
		

		alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + (1 * 1000),
				REPEAT_INTEVAL_MINS_UPLOAD * 60 * 1000, GlobalUpdatePendingIntent);

		updateTimerThread.run();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// User chose not to enable Bluetooth.
		if (requestCode == REQUEST_ENABLE_BT
				&& resultCode == Activity.RESULT_CANCELED) {
			finish();
			return;
		}
		if (requestCode == REQUEST_CREDENTIALS
				&& resultCode == Activity.RESULT_CANCELED) {
			finish();
			return;
		}
		
		BleServiceIntent = new Intent(this, BleAlarmReceiver.class);
		BlePendingIntent = PendingIntent.getBroadcast(this, 2, BleServiceIntent, 0);

		alarmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + (1 * 1000),
				REPEAT_INTEVAL_MINS_BLE * 60 * 1000, BlePendingIntent);

		GlobalUpdateServiceIntent = new Intent(this, GlobalUpdateAlarmReceiver.class);
		GlobalUpdatePendingIntent = PendingIntent.getBroadcast(this, 2, GlobalUpdateServiceIntent,
				0);

		alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + (1 * 1000),
				REPEAT_INTEVAL_MINS_UPLOAD * 60 * 1000, GlobalUpdatePendingIntent);
		
		statusPrefs = getSharedPreferences(Prefs.PREFS_STATUS,
				Context.MODE_PRIVATE);
		
		updateTimerThread.run();
		
		super.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.personal_overview, menu);
		return true;
	}
	
	@Override
	public void onResume() {
	    super.onResume();  // Always call the superclass method first

	    
	    statusPrefs = getSharedPreferences(Prefs.PREFS_STATUS,
				Context.MODE_PRIVATE);

		today = (TextView) findViewById(R.personal_overview.today);
		thisWeek = (TextView) findViewById(R.personal_overview.this_week);
		thisLife = (TextView) findViewById(R.personal_overview.this_life);
		status = (TextView) findViewById(R.personal_overview.status);

		mProgressView = findViewById(R.personal_overview.fetch_progress);
		mContentView = findViewById(R.personal_overview.content);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.personal_overview.login) {
			Intent requestCredsIntent = new Intent(this, LoginActivity.class);
			startActivityForResult(requestCredsIntent, REQUEST_CREDENTIALS);
			return true;
		}
		if (id == R.personal_overview.logout) {

			Editor authEditor = sAuthPrefs.edit();
			authEditor.putString(Auth.PREFS_CREDS_UNAME, null);
			authEditor.putString(Auth.PREFS_CREDS_PASSWORD, null);
			authEditor.commit();

			SharedPreferences sSensorPrefs = getSharedPreferences(
					Sensors.PREFS_SENSORS, Context.MODE_PRIVATE);
			Editor sensorEditor = sAuthPrefs.edit();
			sensorEditor.putLong(Sensors.SENSOR_LIST_COMPLETE_TIME, 0);
			sensorEditor.putString(Sensors.SENSOR_LIST_COMPLETE, null);
			sensorEditor.putString(Sensors.BEACON_SENSOR, null);
			sensorEditor.commit();

			SharedPreferences sStatusPrefs = getSharedPreferences(
					Prefs.PREFS_STATUS, Context.MODE_PRIVATE);
			Editor statusEditor = sStatusPrefs.edit();
			statusEditor.putBoolean(Prefs.STATUS_IN_OFFICE, false);
			statusEditor.putLong(Prefs.STATUS_TOTAL_TIME, 0);
			statusEditor.putLong(Prefs.STATUS_TIME_TODAY, 0);
			statusEditor.putLong(Prefs.STATUS_TIME_WEEK, 0);
			statusEditor.commit();

			DBHelper DB = DBHelper.getDBHelper(this);
			DB.deleteAllRows(DBHelper.DetectionLog.TABLE_NAME);
			
			alarmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			alarmMgr.cancel(BlePendingIntent);
			alarmMgr.cancel(GlobalUpdatePendingIntent);			
			
			Toast t = Toast.makeText(this, "Logged out successfully",
					Toast.LENGTH_LONG);
			t.show();

			Intent requestCredsIntent = new Intent(this, LoginActivity.class);
			startActivityForResult(requestCredsIntent, REQUEST_CREDENTIALS);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// /**
	// * Shows the progress UI and hides the login form.
	// */
	@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
	public void showProgress(final boolean show) {
		// On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
		// for very easy animations. If available, use these APIs to fade-in
		// the progress spinner.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
			int shortAnimTime = getResources().getInteger(
					android.R.integer.config_shortAnimTime);

			mContentView.setVisibility(show ? View.GONE : View.VISIBLE);
			mContentView.animate().setDuration(shortAnimTime)
					.alpha(show ? 0 : 1)
					.setListener(new AnimatorListenerAdapter() {
						@Override
						public void onAnimationEnd(Animator animation) {
							mContentView.setVisibility(show ? View.GONE
									: View.VISIBLE);
						}
					});

			mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
			mProgressView.animate().setDuration(shortAnimTime)
					.alpha(show ? 1 : 0)
					.setListener(new AnimatorListenerAdapter() {
						@Override
						public void onAnimationEnd(Animator animation) {
							mProgressView.setVisibility(show ? View.VISIBLE
									: View.GONE);
						}
					});
		} else {
			// The ViewPropertyAnimator APIs are not available, so simply show
			// and hide the relevant UI components.
			mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
			mContentView.setVisibility(show ? View.GONE : View.VISIBLE);
		}
	}

	private Runnable updateTimerThread = new Runnable() {
		
		long currentTimeInSeconds;
		long timeDifferenceSeconds;
		long displayTime;
		int hours;
		int minutes;
		int seconds;
		int days;

		public void run() {

			if (statusPrefs.getBoolean(Prefs.STATUS_IN_OFFICE, false)) {

				status.setText("Status: in the sense office");
				currentTimeInSeconds = System.currentTimeMillis() / 1000;
				timeDifferenceSeconds = currentTimeInSeconds
						- statusPrefs.getLong(Prefs.STATUS_TIMESTAMP, 0);

				displayTime = statusPrefs.getLong(Prefs.STATUS_TOTAL_TIME, 0)
						+ timeDifferenceSeconds;
				days = (int) displayTime / (24 * 60 * 60);
				hours = (int) (displayTime - 24 * 60 * 60 * days) / (60 * 60);
				minutes = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60)) / (60);
				seconds = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60) - minutes * 60);
				
				thisLife.setText(days + ":" + String.format("%02d", hours)
						+ ":" +String.format("%02d", minutes) + ":"
						+ String.format("%02d", seconds));

				displayTime = statusPrefs.getLong(Prefs.STATUS_TIME_TODAY, 0)
						+ timeDifferenceSeconds;
				days = (int) displayTime / (24 * 60 * 60);
				hours = (int) (displayTime - 24 * 60 * 60 * days) / (60 * 60);
				minutes = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60)) / (60);
				seconds = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60) - minutes * 60);
				today.setText(days + ":" + String.format("%02d", hours)
						+ ":" +String.format("%02d", minutes) + ":"
						+ String.format("%02d", seconds));

				displayTime = statusPrefs.getLong(Prefs.STATUS_TIME_WEEK, 0)
						+ timeDifferenceSeconds;
				days = (int) displayTime / (24 * 60 * 60);
				hours = (int) (displayTime - 24 * 60 * 60 * days) / (60 * 60);
				minutes = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60)) / (60);
				seconds = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60) - minutes * 60);
				thisWeek.setText(days + ":" + String.format("%02d", hours)
						+ ":" +String.format("%02d", minutes) + ":"
						+ String.format("%02d", seconds));

			} else {
				status.setText("Status: not in the sense office");

				displayTime = statusPrefs.getLong(Prefs.STATUS_TOTAL_TIME, 0);
				days = (int) displayTime / (24 * 60 * 60);
				hours = (int) (displayTime - 24 * 60 * 60 * days) / (60 * 60);
				minutes = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60)) / (60);
				seconds = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60) - minutes * 60);
				thisLife.setText(days + ":" + String.format("%02d", hours)
						+ ":" +String.format("%02d", minutes) + ":"
						+ String.format("%02d", seconds));
				
				displayTime = statusPrefs.getLong(Prefs.STATUS_TIME_TODAY, 0);
				days = (int) displayTime / (24 * 60 * 60);
				hours = (int) (displayTime - 24 * 60 * 60 * days) / (60 * 60);
				minutes = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60)) / (60);
				seconds = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60) - minutes * 60);
				today.setText(days + ":" + String.format("%02d", hours)
						+ ":" +String.format("%02d", minutes) + ":"
						+ String.format("%02d", seconds));

				displayTime = statusPrefs.getLong(Prefs.STATUS_TIME_WEEK, 0);
				days = (int) displayTime / (24 * 60 * 60);
				hours = (int) (displayTime - 24 * 60 * 60 * days) / (60 * 60);
				minutes = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60)) / (60);
				seconds = (int) (displayTime - days * (24 * 60 * 60) - hours
						* (60 * 60) - minutes * 60);
				thisWeek.setText(days + ":" + String.format("%02d", hours)
						+ ":" +String.format("%02d", minutes) + ":"
						+ String.format("%02d", seconds));
			}
			timerHandler.postDelayed(this, 1000);
		}
	};
}
