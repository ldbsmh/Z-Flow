package com.sunshine.freeform;

import android.content.Intent;
import android.os.Bundle;

interface IFreeformManager {
    /**
     * Start an activity on the specified display
     * @param intent The intent to start
     * @param options ActivityOptions bundle (must contain launchDisplayId)
     * @param userId The user ID to start the activity as
     * @return Activity start result code
     */
    int startActivity(in Intent intent, in Bundle options, int userId);

    /**
     * Send a PendingIntent
     * @param intentSender The IIntentSender from PendingIntent
     * @param options ActivityOptions bundle
     * @return Result code
     */
    int sendIntentSender(IBinder intentSender, IBinder whitelistToken, in Bundle options);

    /**
     * Check if the service is available
     */
    boolean isAvailable();
}
