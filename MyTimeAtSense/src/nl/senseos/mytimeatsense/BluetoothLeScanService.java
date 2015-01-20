package nl.senseos.mytimeatsense;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

//A service that interacts with the BLE device via the Android BLE API.
public class BluetoothLeScanService extends Service {

	public final static String[] RECOGNIZED_BEACONS = { "05094D656D6F03194002020106020A000703041802180318"
			+ "0000000000000000000000000000000000000000000000000000000000000000000000000000" };
	public final static String SCAN_RESULT = "ble_scan_result";
	public final static String SCAN_RESULT_TIMESTAMP = "ble_scan_result_timestamp";

	private final static String TAG = BluetoothLeScanService.class
			.getSimpleName();
	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	// Stops scanning after 5 seconds.
	private static final long SCAN_PERIOD = 5 * 1000l;
	private boolean beaconFound = false;
	private boolean mScanning;
	private Handler mHandler;

	private void broadcastUpload() {

		final Intent intent = new Intent(this, LocalUpdateService.class);
		intent.putExtra(SCAN_RESULT, beaconFound);
		intent.putExtra(SCAN_RESULT_TIMESTAMP,
				System.currentTimeMillis() / 1000); // (in full seconds!)
		startService(intent);
	}
	
	@Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, startId, startId);
        Log.i("LocalService", "Received start id " + startId + ": " + intent);
        
        initialize();
		scanLeDevice();

        return START_STICKY;
    }

	/**
	 * Initializes a reference to the local Bluetooth adapter.
	 *
	 * @return Return true if the initialization is successful.
	 */
	public boolean initialize() {
		// For API level 18 and above, get a reference to BluetoothAdapter
		// through
		// BluetoothManager.
		if (mBluetoothManager == null) {
			mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
			if (mBluetoothManager == null) {
				Log.e(TAG, "Unable to initialize BluetoothManager.");
				return false;
			}
		}
		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if (mBluetoothAdapter == null) {
			Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
			return false;
		}
		return true;
	}

	// Scan for bluetooth devices and parse results
	private void scanLeDevice() {

		// Stops scanning after a pre-defined scan period.
		if(!mScanning){
			mHandler = new Handler();
			mHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					Log.d(TAG, "stop scanning");
					mScanning = false;
					mBluetoothAdapter.stopLeScan(mLeScanCallback);

					Log.v(TAG, "closing scan. beacon found:" + beaconFound);
					broadcastUpload();
					beaconFound = false;
					stopSelf();
				}
			}, SCAN_PERIOD);
			
			mScanning = true;
			mBluetoothAdapter.startLeScan(mLeScanCallback);
			Log.d(TAG, "start scanning");		
		}	
	}

	// Device scan callback.
	private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

		private byte[] PDU;

		@Override
		public void onLeScan(final BluetoothDevice device, int rssi,
				byte[] scanRecord) {

			PDU = scanRecord;		
			iBeacon res = iBeacon.parseSupportedAd(PDU);
			
			if (res!=null) {
				Log.v(TAG, "Beacon detected");
				beaconFound = true;
			}
//			Log.d("PDU", bytesToHex(PDU));
		}
	};

	// characters used to display hexadecimal numbers
	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

	// convert byte array to hexadecimal string
	public String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}

	/**
	 * 
	 * @param pdu
	 * @return true if the pdu is coming from a beacon in the Sense office
	 */
	protected boolean parseAd(String pdu) {
		
		for (int i = 0; i < RECOGNIZED_BEACONS.length; i++) {
			if (RECOGNIZED_BEACONS[i].equals(pdu)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}