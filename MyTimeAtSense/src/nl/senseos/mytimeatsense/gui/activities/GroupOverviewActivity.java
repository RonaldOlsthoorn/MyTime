package nl.senseos.mytimeatsense.gui.activities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import nl.senseos.mytimeatsense.R;
import nl.senseos.mytimeatsense.R.id;
import nl.senseos.mytimeatsense.R.layout;
import nl.senseos.mytimeatsense.R.menu;
import nl.senseos.mytimeatsense.commonsense.CommonSenseAdapter;
import nl.senseos.mytimeatsense.commonsense.MsgHandler;
import nl.senseos.mytimeatsense.util.DemanesConstants.GroupPrefs;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.Activity;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.SimpleAdapter;

public class GroupOverviewActivity extends Activity {

	public static final String TAG = GroupOverviewActivity.class
			.getSimpleName();
	private ListView scoreList;
	private View progressBar;
	private Handler groupHandler;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_group_overview);

		scoreList = (ListView) findViewById(R.compare_group.list_members);
		progressBar = findViewById(R.compare_group.progress_bar);

		MsgHandler msgHandler = MsgHandler.getInstance();

		groupHandler = new Handler(msgHandler.getLooper()) {
			@Override
			public void handleMessage(Message inputMessage) {
				GroupOverviewActivity.this.runOnUiThread(new postExecute(inputMessage.obj));
			}
		};

		groupHandler.post(new downloadGroupOverview());

		// DownloadGroupOverviewTask task = new DownloadGroupOverviewTask();
		// task.execute();

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.group_overview, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.

		return super.onOptionsItemSelected(item);
	}

	/**
	 * Shows the progress UI and hides the login form.
	 */
	public void showProgress(final boolean show) {
		// On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
		// for very easy animations. If available, use these APIs to fade-in
		// the progress spinner.

		int shortAnimTime = getResources().getInteger(
				android.R.integer.config_shortAnimTime);

		scoreList.setVisibility(show ? View.GONE : View.VISIBLE);
		scoreList.animate().setDuration(shortAnimTime).alpha(show ? 0 : 1)
				.setListener(new AnimatorListenerAdapter() {
					@Override
					public void onAnimationEnd(Animator animation) {
						scoreList
								.setVisibility(show ? View.GONE : View.VISIBLE);
					}
				});

		progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
		progressBar.animate().setDuration(shortAnimTime).alpha(show ? 1 : 0)
				.setListener(new AnimatorListenerAdapter() {
					@Override
					public void onAnimationEnd(Animator animation) {
						progressBar.setVisibility(show ? View.VISIBLE
								: View.GONE);
					}
				});

	}

	private class DownloadGroupOverviewTask extends
			AsyncTask<Void, Void, JSONArray> {

		@Override
		protected void onPreExecute() {
			showProgress(true);
		}

		@Override
		protected JSONArray doInBackground(Void... params) {

			CommonSenseAdapter cs = new CommonSenseAdapter(
					GroupOverviewActivity.this);

			JSONArray result;
			try {

				JSONObject response;

				int res = cs.login();
				Log.e(TAG, "login result: " + res);

				if (res == 0) {

					if (!cs.isGroupMember(GroupPrefs.GROUP_ID)) {
						cs.joinGroup(GroupPrefs.GROUP_ID);
					}
					response = cs.fetchGroupResult();
					result = new JSONArray(response.getJSONArray("data")
							.getJSONObject(0).getString("value"));
					cs.logout();
				} else {

					return null;
				}

			} catch (IOException e) {
				e.printStackTrace();
				return null;
			} catch (JSONException e) {
				e.printStackTrace();
				return null;
			}
			return result;
		}

		@Override
		protected void onPostExecute(JSONArray result) {

			if (result == null) {
				showProgress(false);
				return;
			}

			ArrayList<Map<String, String>> list = new ArrayList<Map<String, String>>(
					result.length());
			SimpleAdapter adapter;

			try {

				for (int i = 0; i < result.length(); i++) {
					JSONObject member = result.getJSONObject(i);
					HashMap<String, String> item = new HashMap<String, String>();

					item.put("name", member.getString("user_name"));
					String[] timeInstance = toDisplayTime(member
							.getInt("total_time"));
					item.put("day", timeInstance[0]);
					item.put("hour", timeInstance[1]);
					item.put("minute", timeInstance[2]);
					item.put("second", timeInstance[3]);
					list.add(item);
				}

				adapter = new SimpleAdapter(GroupOverviewActivity.this, list,
						R.layout.compare_row, new String[] { "name", "day",
								"hour", "minute", "second" }, new int[] {
								R.compare_row.username, R.compare_row.day,
								R.compare_row.hour, R.compare_row.minute,
								R.compare_row.second });

				scoreList.setAdapter(adapter);

			} catch (JSONException e) {
				e.printStackTrace();
			}

			showProgress(false);
		}
	}

	private class downloadGroupOverview implements Runnable {

		@Override
		public void run() {

			Message msg = Message.obtain();

			CommonSenseAdapter cs = new CommonSenseAdapter(
					GroupOverviewActivity.this);

			JSONArray result;
			try {

				JSONObject response;

				int res = cs.login();
				Log.e(TAG, "login result: " + res);

				if (res == 0) {

					if (!cs.isGroupMember(GroupPrefs.GROUP_ID)) {
						cs.joinGroup(GroupPrefs.GROUP_ID);
					}
					response = cs.fetchGroupResult();
					result = new JSONArray(response.getJSONArray("data")
							.getJSONObject(0).getString("value"));
					cs.logout();
				} else {
					msg.obj = null;
					groupHandler.sendMessage(msg);
					return;
				}

			} catch (IOException e) {
				e.printStackTrace();
				msg.obj = null;
				groupHandler.sendMessage(msg);
				return;
			} catch (JSONException e) {
				e.printStackTrace();
				msg.obj = null;
				groupHandler.sendMessage(msg);
				return;
			}
			msg.obj = result;
			groupHandler.sendMessage(msg);
		}
	}

	private String[] toDisplayTime(int timeInSec) {

		int days = timeInSec / (24 * 60 * 60);
		int hours = (timeInSec - (days * 24 * 60 * 60)) / (60 * 60);
		int minutes = (timeInSec - (days * 24 * 60 * 60) - (hours * 60 * 60)) / (60);
		int seconds = timeInSec - (days * 24 * 60 * 60) - (hours * 60 * 60)
				- (minutes * 60);

		return new String[] { Integer.toString(days),
				String.format("%02d", hours), String.format("%02d", minutes),
				String.format("%02d", seconds) };
	}
	
	
	private class postExecute implements Runnable{
		
		private Object message;
		
		public postExecute(Object msg){
			message=msg;
		}

		@Override
		public void run() {
			onPostExecute((JSONArray) message);
		}
		
		
	}
	
	private void onPostExecute(JSONArray result) {
		
		if (result == null) {
			showProgress(false);
			return;
		}

		ArrayList<Map<String, String>> list = new ArrayList<Map<String, String>>(
				result.length());
		SimpleAdapter adapter;

		try {

			for (int i = 0; i < result.length(); i++) {
				JSONObject member = result.getJSONObject(i);
				HashMap<String, String> item = new HashMap<String, String>();

				item.put("name", member.getString("user_name"));
				String[] timeInstance = toDisplayTime(member
						.getInt("total_time"));
				item.put("day", timeInstance[0]);
				item.put("hour", timeInstance[1]);
				item.put("minute", timeInstance[2]);
				item.put("second", timeInstance[3]);
				list.add(item);
			}

			adapter = new SimpleAdapter(GroupOverviewActivity.this, list,
					R.layout.compare_row, new String[] { "name", "day", "hour",
							"minute", "second" }, new int[] {
							R.compare_row.username, R.compare_row.day,
							R.compare_row.hour, R.compare_row.minute,
							R.compare_row.second });

			scoreList.setAdapter(adapter);

		} catch (JSONException e) {
			e.printStackTrace();
		}

		showProgress(false);
	}
}
