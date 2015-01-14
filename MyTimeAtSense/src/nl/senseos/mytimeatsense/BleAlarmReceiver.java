package nl.senseos.mytimeatsense;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BleAlarmReceiver extends BroadcastReceiver{

	private final static String TAG = BroadcastReceiver.class
			.getSimpleName();

	@Override
	public void onReceive(Context context, Intent intent) {

		Log.d(TAG,"onReceive BLE");
		Intent mIntent = new Intent(context, BluetoothLeScanService.class);
		context.startService(mIntent);
	}

}