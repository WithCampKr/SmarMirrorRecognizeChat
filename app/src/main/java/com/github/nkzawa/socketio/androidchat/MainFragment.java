package com.github.nkzawa.socketio.androidchat;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;


/**
 * A chat fragment containing messages view and input form.
 */
public class MainFragment extends Fragment {

	private ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected( ComponentName name, IBinder service ) {
			mSpeechRecognitionService = ( (SpeechRecognitionService.LocalBinder) service ).getService();
		}

		@Override
		public void onServiceDisconnected( ComponentName name ) {
			mSpeechRecognitionService = null;
		}

	};
	private Handler mHandler;
	private SpeechRecognitionService mSpeechRecognitionService;


	private RecyclerView mMessagesView;
	private EditText mInputMessageView;
	private List<Message> mMessages = new ArrayList<Message>();
	private RecyclerView.Adapter mAdapter;
	private Socket mSocket;

	private Boolean isConnected = true;

	public MainFragment() {
		super();
	}

	@Override
	public void onAttach( Activity activity ) {
		super.onAttach( activity );
		mAdapter = new MessageAdapter( activity, mMessages );
	}

	@Override
	public void onCreate( Bundle savedInstanceState ) {
		super.onCreate( savedInstanceState );

		ChatApplication app = (ChatApplication) getActivity().getApplication();
		mSocket = app.getSocket();
		mSocket.on( Socket.EVENT_CONNECT, onConnect );
		mSocket.on( Socket.EVENT_DISCONNECT, onDisconnect );
		mSocket.on( Socket.EVENT_CONNECT_ERROR, onConnectError );
		mSocket.on( Socket.EVENT_CONNECT_TIMEOUT, onConnectError );
		mSocket.on( "chat message", onNewMessage );
		mSocket.connect();
	}

	@Override
	public View onCreateView( LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState ) {
		return inflater.inflate( R.layout.fragment_main, container, false );
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		getActivity().unbindService( mServiceConnection );
		mSocket.disconnect();

		mSocket.off( Socket.EVENT_CONNECT, onConnect );
		mSocket.off( Socket.EVENT_DISCONNECT, onDisconnect );
		mSocket.off( Socket.EVENT_CONNECT_ERROR, onConnectError );
		mSocket.off( Socket.EVENT_CONNECT_TIMEOUT, onConnectError );
		mSocket.off( "chat message", onNewMessage );
	}

	@Override
	public void onViewCreated( View view, Bundle savedInstanceState ) {
		super.onViewCreated( view, savedInstanceState );

		getActivity().bindService( new Intent( getActivity(), SpeechRecognitionService.class ), mServiceConnection, Context.BIND_AUTO_CREATE );

		ImageButton btn = (ImageButton) view.findViewById( R.id.start_recognize );
		btn.setOnClickListener( new View.OnClickListener() {

			@Override
			public void onClick( View v ) {
				addLog( getString( R.string.recognizing ) );
				mSpeechRecognitionService.startRecognition( new SpeechRecognition.ResultCallback() {

					@Override
					public void onResults( final List<String> results ) {
						mHandler.post( new Runnable() {
							@Override
							public void run() {
								addMessage( "You", results.get( 0 ) );
							}
						} );
						mSocket.emit( "chat message", results.get( 0 ) );
					}

					@Override
					public void onError( final String reason ) {
						mHandler.post( new Runnable() {
							@Override
							public void run() {
								addLog( reason );
							}
						} );
					}

				} );
			}

		} );
		mHandler = new Handler();

		mMessagesView = (RecyclerView) view.findViewById( R.id.messages );
		mMessagesView.setLayoutManager( new LinearLayoutManager( getActivity() ) );
		mMessagesView.setAdapter( mAdapter );

		mInputMessageView = (EditText) view.findViewById( R.id.message_input );
		mInputMessageView.setOnEditorActionListener( new TextView.OnEditorActionListener() {
			@Override
			public boolean onEditorAction( TextView v, int id, KeyEvent event ) {
				if ( id == R.id.send || id == EditorInfo.IME_NULL ) {
					attemptSend();
					return true;
				}
				return false;
			}
		} );

		ImageButton sendButton = (ImageButton) view.findViewById( R.id.send_button );
		sendButton.setOnClickListener( new View.OnClickListener() {
			@Override
			public void onClick( View v ) {
				attemptSend();
			}
		} );
	}

	private void addLog( String message ) {
		mMessages.add( new Message.Builder( Message.TYPE_LOG )
				.message( message ).build() );
		mAdapter.notifyItemInserted( mMessages.size() - 1 );
		scrollToBottom();
	}

	private void addMessage( String username, String message ) {
		mMessages.add( new Message.Builder( Message.TYPE_MESSAGE )
				.username( username ).message( message ).build() );
		mAdapter.notifyItemInserted( mMessages.size() - 1 );
		scrollToBottom();
	}

	private void attemptSend() {
		if ( !mSocket.connected() ) return;

		String message = mInputMessageView.getText().toString().trim();
		if ( TextUtils.isEmpty( message ) ) {
			mInputMessageView.requestFocus();
			return;
		}

		mInputMessageView.setText( "" );
//		addMessage( "Wow", message );

		// perform the sending message attempt.
		mSocket.emit( "chat message", message );
	}

	private void scrollToBottom() {
		mMessagesView.scrollToPosition( mAdapter.getItemCount() - 1 );
	}

	private Emitter.Listener onConnect = new Emitter.Listener() {
		@Override
		public void call( Object... args ) {
			getActivity().runOnUiThread( new Runnable() {
				@Override
				public void run() {
					if ( !isConnected ) {
						Toast.makeText( getActivity().getApplicationContext(),
								R.string.connect, Toast.LENGTH_LONG ).show();
						isConnected = true;
					}
				}
			} );
		}
	};

	private Emitter.Listener onDisconnect = new Emitter.Listener() {
		@Override
		public void call( Object... args ) {
			getActivity().runOnUiThread( new Runnable() {
				@Override
				public void run() {
					isConnected = false;
					Toast.makeText( getActivity().getApplicationContext(),
							R.string.disconnect, Toast.LENGTH_LONG ).show();
				}
			} );
		}
	};

	private Emitter.Listener onConnectError = new Emitter.Listener() {
		@Override
		public void call( Object... args ) {
			getActivity().runOnUiThread( new Runnable() {
				@Override
				public void run() {
					Toast.makeText( getActivity().getApplicationContext(),
							R.string.error_connect, Toast.LENGTH_LONG ).show();
				}
			} );
		}
	};

	private Emitter.Listener onNewMessage = new Emitter.Listener() {
		@Override
		public void call( final Object... args ) {
			getActivity().runOnUiThread( new Runnable() {
				@Override
				public void run() {
					JSONObject data = (JSONObject) args[0];
					String message;
					try {
						message = data.getString( "msg" );
					}
					catch ( JSONException e ) {
						return;
					}

					addMessage( "Wow", message );
				}
			} );
		}
	};

}

