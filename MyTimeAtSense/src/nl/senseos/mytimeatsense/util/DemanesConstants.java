package nl.senseos.mytimeatsense.util;


public class DemanesConstants {

	public static class StatusPrefs {

		public static final String PREFS_STATUS = "prefs_status";

		public static final String STATUS_IN_OFFICE = "status_in_office";

		public static final String STATUS_TOTAL_TIME = "status_total_time";

		public static final String STATUS_TIME_TODAY = "status_time_today";

		public static final String STATUS_TIME_WEEK = "status_time_week";

		public static final String STATUS_TIMESTAMP = "status_time_stamp";

	}

	public static class GroupPrefs {

		public static final String PREFS_GROUP = "prefs_group";

		public static final int GROUP_ID = 17335;
		
		public static final int GROUP_SENSOR_ID = 799301;

		public static final String GROUP_NAME = "Demanes";

		public static final String GROUP_PASSWORD = "87f95196987d8c3bf339e2a52be957f4";

	}
	
	/**
	 * Set of predefined names for the data types that CommonSense supports. Useful for creating new
	 * sensor data value Intents.
	 * 
	 * @author Steven Mulder <steven@sense-os.nl>
	 */
	public static class SenseDataTypes {

	    public static final String BOOL = "bool";
	    public static final String FLOAT = "float";    
	    public static final String ARRAY = "array";    
	    public static final String INT = "int";
	    public static final String JSON = "json";
	    public static final String STRING = "string";
	    public static final String FILE = "file";    
	    public static final String JSON_TIME_SERIES = "json time serie";

	    private SenseDataTypes() {
	        // private constructor to prevent instantiation
	    }
	}
	
	public static class Prefs {

		public static final String PREFS_DATA = "prefs_data";
		public static final String PREFS_CONNECTION = "prefs_connection";

	}
	public static class Url{
		/** Host name of CommonSense API */
	    public static final String API_URL = "https://api.sense-os.nl/";
	    public static final String SENSORS_URL = API_URL+"sensors";
	    public static final String SENSOR_DEVICE_URL = API_URL + "sensors/%1/device";
	    public static final String LOGIN_URL = API_URL+"login";
	    public static final String LOGOUT_URL = API_URL+"logout";
	    
	}
	
	public static class Sensors {
		

		public static final String PREFS_SENSORS = "prefs_sensors";

		public static final String SENSOR_LIST_COMPLETE="sensor_list_complete";

		public static final String SENSOR_LIST_COMPLETE_TIME="sensor_list_complete_time";		
		/** Default page size for getting lists at CommonSense */
	    public static final int PAGE_SIZE = 1000;
	    	    
		public static final String SENSOR_NAME="name";		

		public static final String SENSOR_DISPLAY_NAME="display_name";		

		public static final String SENSOR_ID="id";		
				
		public static final String BEACON_SENSOR_NAME="beacon_sensor";
		
		public static final String BEACON_SENSOR_DISPLAY_NAME="beacon_sensor";
		
		public static final String BEACON_SENSOR_DESCRIPTION="detect beacon at the sense os office";

		public static final String BEACON_SENSOR = "beacon_sensor_json";
		
	    
	    
	}

	public static class Auth {
		
		public static final String PREFS_CREDS = "prefs_creds";
		
		public static final String PREFS_CREDS_UNAME = "username";
		
		public static final String PREFS_CREDS_PASSWORD = "password";
		
		/**
		 * Key for login preference for session cookie.
		 * 
		 */
		public static final String LOGIN_COOKIE = "login_cookie";
		/**
		 * Key for login preference for session id.
		 */	
		public static final String LOGIN_SESSION_ID = "session_id";	
		
		/**
         * Key for storing the online device type.
         * 
         * @see SensePrefs#AUTH_PREFS
         */
        public static final String DEVICE_TYPE = "device_type";
	}
}
