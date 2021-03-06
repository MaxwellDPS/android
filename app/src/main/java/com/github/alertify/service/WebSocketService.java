package com.github.alertify.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.github.alertify.MissedMessageUtil;
import com.github.alertify.NotificationSupport;
import com.github.alertify.R;
import com.github.alertify.Settings;
import com.github.alertify.Utils;
import com.github.alertify.api.ClientFactory;
import com.github.alertify.client.ApiClient;
import com.github.alertify.client.api.MessageApi;
import com.github.alertify.client.model.Message;
import com.github.alertify.log.Log;
import com.github.alertify.log.UncaughtExceptionHandler;
import com.github.alertify.init.messages.Extras;
import com.github.alertify.init.messages.MessagesActivity;
import com.github.alertify.picasso.PicassoHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.github.alertify.WEA.CriticalNotify;
import com.github.alertify.WEA.CBUtils.SmsCbConstants;

public class WebSocketService extends Service {

    public static final String NEW_MESSAGE_BROADCAST =
            WebSocketService.class.getName() + ".NEW_MESSAGE";

    private static final long NOT_LOADED = -2;

    private Settings settings;
    private WebSocketConnection connection;

    private AtomicLong lastReceivedMessage = new AtomicLong(NOT_LOADED);
    private MissedMessageUtil missingMessageUtil;

    private PicassoHandler picassoHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        ApiClient client =
                ClientFactory.clientToken(settings.url(), settings.sslSettings(), settings.token());
        missingMessageUtil = new MissedMessageUtil(client.createService(MessageApi.class));
        Log.i("Create " + getClass().getSimpleName());
        picassoHandler = new PicassoHandler(this, settings);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (connection != null) {
            connection.close();
        }
        Log.w("Destroy " + getClass().getSimpleName());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.init(this);

        if (connection != null) {
            connection.close();
        }

        Log.i("Starting " + getClass().getSimpleName());
        super.onStartCommand(intent, flags, startId);
        new Thread(this::startPushService).run();

        return START_STICKY;
    }

    private void startPushService() {
        UncaughtExceptionHandler.registerCurrentThread();
        foreground(getString(R.string.websocket_init));

        if (lastReceivedMessage.get() == NOT_LOADED) {
            missingMessageUtil.lastReceivedMessage(lastReceivedMessage::set);
        }

        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        connection =
                new WebSocketConnection(
                                settings.url(),
                                settings.sslSettings(),
                                settings.token(),
                                cm,
                                alarmManager)
                        .onOpen(this::onOpen)
                        .onClose(() -> foreground(getString(R.string.websocket_closed)))
                        .onBadRequest(this::onBadRequest)
                        .onNetworkFailure(
                                (min) -> foreground(getString(R.string.websocket_failed, min)))
                        .onDisconnect(this::onDisconnect)
                        .onMessage(this::onMessage)
                        .onReconnected(this::notifyMissedNotifications)
                        .start();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        ReconnectListener receiver = new ReconnectListener(this::doReconnect);
        registerReceiver(receiver, intentFilter);

        picassoHandler.updateAppIds();
    }

    private void onDisconnect() {
        foreground(getString(R.string.websocket_no_network));
    }

    private void doReconnect() {
        if (connection == null) {
            return;
        }

        connection.scheduleReconnect(5);
    }

    private void onBadRequest(String message) {
        foreground(getString(R.string.websocket_could_not_connect, message));
    }

    private void onOpen() {
        foreground(getString(R.string.websocket_listening, settings.url()));
    }

    private void notifyMissedNotifications() {
        long messageId = lastReceivedMessage.get();
        if (messageId == NOT_LOADED) {
            return;
        }

        List<Message> messages = missingMessageUtil.missingMessages(messageId);

        if (messages.size() > 5) {
            onGroupedMessages(messages);
        } else {
            for (Message message : messages) {
                onMessage(message);
            }
        }
    }

    private void onGroupedMessages(List<Message> messages) {
        long highestPriority = 0;
        for (Message message : messages) {
            if (lastReceivedMessage.get() < message.getId()) {
                lastReceivedMessage.set(message.getId());
                highestPriority = Math.max(highestPriority, message.getPriority());
            }
            broadcast(message);
        }
        int size = messages.size();
        showNotification(
                NotificationSupport.ID.GROUPED,
                getString(R.string.missed_messages),
                getString(R.string.grouped_message, size),
                highestPriority,
                null);
    }

    private void onMessage(Message message) {
        if (lastReceivedMessage.get() < message.getId()) {
            lastReceivedMessage.set(message.getId());
        }

        broadcast(message);
        showNotification(
                message.getId(),
                message.getTitle(),
                message.getMessage(),
                message.getPriority(),
                message.getExtras(),
                message.getAppid());
    }

    private void broadcast(Message message) {
        Intent intent = new Intent();
        intent.setAction(NEW_MESSAGE_BROADCAST);
        intent.putExtra("message", Utils.JSON.toJson(message));
        sendBroadcast(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void foreground(String message) {
        Intent notificationIntent = new Intent(this, MessagesActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification =
                new NotificationCompat.Builder(this, NotificationSupport.Channel.FOREGROUND)
                        .setSmallIcon(R.drawable.ic_alertify)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setShowWhen(false)
                        .setWhen(0)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setContentIntent(pendingIntent)
                        .setColor(
                                ContextCompat.getColor(
                                        getApplicationContext(), R.color.colorPrimary))
                        .build();

        startForeground(NotificationSupport.ID.FOREGROUND, notification);
    }

    private void showNotification(
            int id, String title, String message, long priority, Map<String, Object> extras) {
        showNotification(id, title, message, priority, extras, -1L);
    }

    private void showNotification(
            long id,
            String title,
            String message,
            long priority,
            Map<String, Object> extras,
            Long appid) {

        Intent intent;

        String intentUrl =
                Extras.getNestedValue(
                        String.class, extras, "android::action", "onReceive", "intentUrl");

        if (intentUrl != null) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(intentUrl));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        String url =
                Extras.getNestedValue(String.class, extras, "client::notification", "click", "url");

        if (url != null) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
        } else {
            intent = new Intent(this, MessagesActivity.class);
        }

        if (priority <= 89) {
            PendingIntent contentIntent =
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder b =
                    new NotificationCompat.Builder(
                            this, NotificationSupport.convertPriorityToChannel(priority));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                showNotificationGroup(priority);
            }

            b.setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.ic_alertify)
                    .setLargeIcon(picassoHandler.getIcon(appid))
                    .setTicker(getString(R.string.app_name) + " - " + title)
                    .setGroup(NotificationSupport.Group.MESSAGES)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                    .setLights(Color.CYAN, 1000, 5000)
                    .setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary))
                    .setContentIntent(contentIntent);

            NotificationManager notificationManager =
                    (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(Utils.longToInt(id), b.build());
        }else if (priority <= 99) {
            int messageType = 0;
            boolean isCMAS = true;
            switch(title.toLowerCase()){
                case "president":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL;
                    break;
                case "extreme":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED;
                    break;
                case "severe":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY;
                    break;
                case "amber":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY;
                    break;
                case "public":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PUBLIC_SAFETY;
                    break;
                case "rmt":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST;
                    break;
                case "statetest":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_STATE_LOCAL_TEST;
                    break;
                case "broadcast":
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_OPERATOR_DEFINED_USE;
                    break;
                case "tsunami":
                    messageType = SmsCbConstants.MESSAGE_ID_ETWS_TSUNAMI_WARNING;
                    isCMAS = false;
                    break;
                case "earthquake":
                    messageType = SmsCbConstants.MESSAGE_ID_ETWS_EARTHQUAKE_WARNING;
                    isCMAS = false;
                    break;
                case "et":
                    messageType = SmsCbConstants.MESSAGE_ID_ETWS_EARTHQUAKE_AND_TSUNAMI_WARNING;
                    isCMAS = false;
                    break;
                case "etws":
                    messageType = SmsCbConstants.MESSAGE_ID_ETWS_OTHER_EMERGENCY_TYPE;
                    isCMAS = false;
                    break;
                case "etwstest":
                    messageType = SmsCbConstants.MESSAGE_ID_ETWS_TEST_MESSAGE;
                    isCMAS = false;
                    break;
                default:
                    messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_Critical;
            }
            CriticalNotify.AlertCritical(messageType, message,this, true, isCMAS);
        }else{
                int messageType = 0;
                boolean isCMAS = true;
                switch(title.toLowerCase()){
                    case "president":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL;
                        break;
                    case "extreme":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED;
                        break;
                    case "severe":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY;
                        break;
                    case "amber":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY;
                        break;
                    case "public":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PUBLIC_SAFETY;
                        break;
                    case "rmt":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST;
                        break;
                    case "statetest":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_STATE_LOCAL_TEST;
                        break;
                    case "broadcast":
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_OPERATOR_DEFINED_USE;
                        break;
                    case "tsunami":
                        messageType = SmsCbConstants.MESSAGE_ID_ETWS_TSUNAMI_WARNING;
                        isCMAS = false;
                        break;
                    case "earthquake":
                        messageType = SmsCbConstants.MESSAGE_ID_ETWS_EARTHQUAKE_WARNING;
                        isCMAS = false;
                        break;
                    case "et":
                        messageType = SmsCbConstants.MESSAGE_ID_ETWS_EARTHQUAKE_AND_TSUNAMI_WARNING;
                        isCMAS = false;
                        break;
                    case "etws":
                        messageType = SmsCbConstants.MESSAGE_ID_ETWS_OTHER_EMERGENCY_TYPE;
                        isCMAS = false;
                        break;
                    case "etwstest":
                        messageType = SmsCbConstants.MESSAGE_ID_ETWS_TEST_MESSAGE;
                        isCMAS = false;
                        break;
                    default:
                        messageType = SmsCbConstants.MESSAGE_ID_CMAS_ALERT_Critical;
                }
                CriticalNotify.AlertCritical(messageType, message,this, false, isCMAS);
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    public void showNotificationGroup(long priority) {
        Intent intent = new Intent(this, MessagesActivity.class);
        PendingIntent contentIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b =
                new NotificationCompat.Builder(
                        this, NotificationSupport.convertPriorityToChannel(priority));

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.ic_alertify)
                .setTicker(getString(R.string.app_name))
                .setGroup(NotificationSupport.Group.MESSAGES)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentTitle(getString(R.string.grouped_notification_text))
                .setGroupSummary(true)
                .setContentText(getString(R.string.grouped_notification_text))
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary))
                .setContentIntent(contentIntent);

        NotificationManager notificationManager =
                (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(-5, b.build());
    }
}
