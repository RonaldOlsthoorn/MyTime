package nl.senseos.mytimeatsense.commonsense;

import android.os.HandlerThread;

public class MsgHandler extends HandlerThread {

	private static final String name = MsgHandler.class.getSimpleName();
	private static final String TAG = MsgHandler.class.getSimpleName();
	
	private static MsgHandler instance;
	
	public static MsgHandler getInstance(){
		
		if(null!=instance){
			return instance;
		}
		
		instance = new MsgHandler(name);
		instance.start();
		
		return instance;
	}	
	
	private MsgHandler(String name) {
		super(name);
	}

	
	
}
