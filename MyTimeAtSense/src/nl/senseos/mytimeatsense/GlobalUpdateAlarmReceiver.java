package nl.senseos.mytimeatsense;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class GlobalUpdateAlarmReceiver extends BroadcastReceiver{

	private final static String TAG = BroadcastReceiver.class
			.getSimpleName();

	@Override
	public void onReceive(Context context, Intent intent) {

		Log.d(TAG,"onReceive GlobalUpdate");
		Intent mIntent = new Intent(context, GlobalUpdateService.class);
		context.startService(mIntent);
	}
}
