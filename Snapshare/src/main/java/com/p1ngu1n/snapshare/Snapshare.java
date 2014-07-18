package com.p1ngu1n.snapshare;

/**
 Snapshare.java created on 6/26/13.

 Copyright (C) 2013 Sebastian Stammler <stammler@cantab.net>

 This file is part of Snapshare.

 Snapshare is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Snapshare is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 a gazillion times. If not, see <http://www.gnu.org/licenses/>.
 */

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.URLUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.newInstance;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

public class Snapshare implements IXposedHookLoadPackage {
    /** Rotation mode */
    private static int ROTATION_MODE;
    /** Preferred adjustment method */
    private static int ADJUST_METHOD;
    /** Debugging */
    private static boolean DEBUGGING;

    /** Snapchat's version */
    private static int SNAPCHAT_VERSION;

    /** After calling initSnapPreviewFragment() below, we set the
     * initializedUri to the current media's Uri to prevent another call of onCreate() to initialize
     * the media again. E.g. onCreate() is called again if the phone is rotated. */
    private Uri initializedUri;
    private XSharedPreferences prefs = new XSharedPreferences("com.p1ngu1n.snapshare");

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.snapchat.android"))
            return;

        refreshPrefs();

        /** thanks to KeepChat for the following snippet: **/
        int version;
        try {
            xposedDebug("----------------- SNAPSHARE HOOKED -----------------", false);
            Object activityThread = callStaticMethod(findClass("android.app.ActivityThread", null), "currentActivityThread");
            Context context = (Context) callMethod(activityThread, "getSystemContext");
            version = context.getPackageManager().getPackageInfo(lpparam.packageName, 0).versionCode;

            PackageInfo piSnapChat = context.getPackageManager().getPackageInfo(lpparam.packageName, 0);
            xposedDebug("SnapChat Version: " + piSnapChat.versionName + " (" + piSnapChat.versionCode + ")", false);

            PackageInfo piSnapshare = context.getPackageManager().getPackageInfo(this.getClass().getPackage().getName(), 0);
            xposedDebug("Snapshare Version: " + piSnapshare.versionName + " (" + piSnapshare.versionCode + ")\n", false);
        }
        catch (Exception e) {
            XposedBridge.log("Snapshare: exception while trying to get version info. (" + e.getMessage() + ")");
            return;
        }

        if(version >= 323) {
            SNAPCHAT_VERSION = Obfuscator.FIVE_ZERO_TWENTYTHREE;
        }
        else if(version >= 298) {
            SNAPCHAT_VERSION = Obfuscator.FIVE_ZERO_NINE;
        }
        else if(version >= 274) {
            SNAPCHAT_VERSION = Obfuscator.FIVE_ZERO_TWO;
        }
        else if(version >= 222) {
            SNAPCHAT_VERSION = Obfuscator.FOUR_ONE_TWELVE;
        }
        else if(version >= 218) {
            SNAPCHAT_VERSION = Obfuscator.FOUR_ONE_TEN;
        }
        else if(version >= 181) {
            SNAPCHAT_VERSION = Obfuscator.FOUR_22;
        }
        else if(version >= 175) {
            SNAPCHAT_VERSION = Obfuscator.FOUR_21;
        }
        else if(version < 175) {
            SNAPCHAT_VERSION = Obfuscator.FOUR_20;
        }

        final Class SnapCapturedEventClass = findClass("com.snapchat.android.util.eventbus.SnapCapturedEvent", lpparam.classLoader);
        final Media media = new Media(); // a place to store the media

        /**
         * Here the main work happens. We hook after the onCreate() call of the main Activity
         * to create a sensible media object.
         */
        findAndHookMethod("com.snapchat.android.LandingPageActivity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                refreshPrefs();
                xposedDebug("----------------- SNAPSHARE STARTED -----------------", false);
                Object thiz = param.thisObject;
                // Get intent, action and MIME type
                Intent intent = (Intent) callSuperMethod(thiz, "getIntent");
                String type = intent.getType();
                String action = intent.getAction();
                xposedDebug("Intent type: " + type + ", intent action:" + action);

                // Check if this is a normal launch of Snapchat or actually called by Snapshare
                if (type != null && Intent.ACTION_SEND.equals(action)) {
                    Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                    // Check for bogus call
                    if (mediaUri == null) {
                        return;
                    }
                    /* We check if the current media got already initialized and should exit instead
                     * of doing the media initialization again. This check is necessary
                     * because onCreate() is also called if the phone is just rotated. */
                    if (initializedUri == mediaUri) {
                        xposedDebug("Media already initialized, exit onCreate() hook");
                        return;
                    }

                    ContentResolver thizContentResolver = (ContentResolver) callSuperMethod(thiz, "getContentResolver");
                    if (type.startsWith("image/")) {
                        xposedDebug("Image URI: " + mediaUri.toString());
                        try {
                            /* TODO: use BitmapFactory with inSampleSize magic to avoid using too much memory,
                             * see http://developer.android.com/training/displaying-bitmaps/load-bitmap.html#load-bitmap */
                            Bitmap bitmap = MediaStore.Images.Media.getBitmap(thizContentResolver, mediaUri);
                            int width = bitmap.getWidth();
                            int height = bitmap.getHeight();
                            xposedDebug("Image shared, size: " + width + " x " + height + " (w x h)");

                            // Landscape images have to be rotated 90 degrees clockwise for Snapchat to be displayed correctly
                            if (width > height && ROTATION_MODE != Commons.ROTATION_NONE) {
                                xposedDebug("Landscape image detected, rotating image " + ROTATION_MODE + " degrees");
                                Matrix matrix = new Matrix();
                                matrix.setRotate(ROTATION_MODE);
                                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                                // resetting width and height
                                width = bitmap.getWidth();
                                height = bitmap.getHeight();
                            }

                            /**
                             * Scaling and cropping mayhem
                             *
                             * Snapchat will break if the image is too large and it will scale the image up if the
                             * Display rectangle (DisplayMetrics.widthPixels x DisplayMetrics.heightPixels rectangle)
                             * is larger than the image.
                             *
                             * So, we sample the image down such that the Display rectangle fits into it and touches one side.
                             * Then we crop the picture to that rectangle
                             */
                            DisplayMetrics dm = new DisplayMetrics();
                            ((WindowManager) callSuperMethod(thiz, "getWindowManager")).getDefaultDisplay().getMetrics(dm);
                            int dWidth = dm.widthPixels;
                            int dHeight = dm.heightPixels;

                            xposedDebug("Display metrics: " + dWidth + " x " + dHeight + " (w x h)");
                            // DisplayMetrics' values depend on the phone's tilt, so we normalize them to Portrait mode
                            if (dWidth > dHeight) {
                                xposedDebug("Normalizing display metrics to Portrait mode.");
                                int temp = dWidth;
                                //noinspection SuspiciousNameCombination
                                dWidth = dHeight;
                                dHeight = temp;
                            }

                            if(ADJUST_METHOD == Commons.ADJUST_CROP) {
                                int imageToDisplayRatio = width * dHeight - height * dWidth;
                                if (imageToDisplayRatio > 0) {
                                    // i.e., width/height > dWidth/dHeight, so have to crop from left and right:
                                    int newWidth = (dWidth * height / dHeight);
                                    xposedDebug("New width after cropping left and right: " + newWidth);
                                    bitmap = Bitmap.createBitmap(bitmap, (width - newWidth) / 2, 0, newWidth, height);
                                } else if (imageToDisplayRatio < 0) {
                                    // i.e., width/height < dWidth/dHeight, so have to crop from top and bottom:
                                    int newHeight = (dHeight * width / dWidth);
                                    xposedDebug("New height after cropping top and bottom: " + newHeight);
                                    bitmap = Bitmap.createBitmap(bitmap, 0, (height - newHeight) / 2, width, newHeight);
                                }

                                /* If the image properly covers the Display rectangle, we mark it as a "large" image
                                 and are going to scale it down. We make this distinction because we don't wanna
                                 scale the image up if it is smaller than the Display rectangle. */
                                boolean largeImage = ((width > dWidth) & (height > dHeight));
                                xposedDebug(largeImage ? "Large image, scaling down" : "Small image");
                                if (largeImage) {
                                    bitmap = Bitmap.createScaledBitmap(bitmap, dWidth, dHeight, true);
                                }
                                // Scaling and cropping finished, ready to let Snapchat display our result
                            }
                            else {
                                // we are going to scale the image down and place a black background behind it
                                Bitmap background = Bitmap.createBitmap(dWidth, dHeight, Bitmap.Config.ARGB_8888);
                                background.eraseColor(Color.BLACK);
                                Canvas canvas = new Canvas(background);
                                Matrix transform = new Matrix();
                                float scale = dWidth / (float)width;
                                float xTrans = 0;
                                if(ADJUST_METHOD == Commons.ADJUST_NONE) {
                                    // Remove scaling and add some translation
                                    scale = 1;
                                    xTrans = dWidth/2 - width/2;
                                }
                                float yTrans = dHeight/2 - scale*height/2;

                                transform.preScale(scale, scale);
                                transform.postTranslate(xTrans, yTrans);
                                Paint paint = new Paint();
                                paint.setFilterBitmap(true);
                                canvas.drawBitmap(bitmap, transform, paint);
                                bitmap = background;
                            }

                            // Make Snapchat show the image
                            media.setContent(bitmap);
                        } catch (FileNotFoundException e) {
                            xposedDebug("File not found!\n" + e.getMessage());
                        } catch (IOException e) {
                            xposedDebug("IO Error!\n" + e.getMessage());
                        }
                    }
                    else if (type.startsWith("video/")) {
                        // Snapchat expects the video URI to be in the file:// format, not content://
                        if (URLUtil.isFileUrl(mediaUri.toString()))
                        {
                            media.setContent(mediaUri);
                            xposedDebug("Already had File URI: " + mediaUri.toString());
                        }
                        // No file URI, so we have to convert it
                        else {
                            String [] proj = {MediaStore.Images.Media.DATA};
                            Cursor cursor = thizContentResolver.query(mediaUri, proj, null, null, null);

                            if (cursor != null) {
                                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                                cursor.moveToFirst();
                                String filePath = cursor.getString(column_index);
                                cursor.close();

                                // Convert filepath to URI, make Snapchat show the video
                                Uri videoUri = Uri.fromFile(new File(filePath));
                                media.setContent(videoUri);
                                xposedDebug("Converted content URI to file URI " + videoUri.toString());
                            } else {
                                xposedDebug("Couldn't resolve content URI to file path!\nContent URI: " + mediaUri.toString());
                            }
                        }
                    }
                    /* Finally the image or video is marked as initialized to prevent reinitialisation of
                     * the SnapCapturedEvent in case of a screen rotation (because onCreate() is then called).
                     * This way, it is made sure that a shared image or media is only initialized and then
                     * showed in a SnapPreviewFragment once.
                     * Also, if Snapchat is used normally after being launched by Snapshare, a screen rotation
                     * while in the SnapPreviewFragment, would draw the shared image or video instead of showing
                     * what has just been recorded by the camera. */
                    initializedUri = mediaUri;
                }
                else {
                    xposedDebug("Regular call of Snapchat.");
                    initializedUri = null;
                }
            }

        });
        /** 
         * We needed to find a method that got called after the camera was ready. 
         * refreshFlashButton is the only method that falls under this category.
         * As a result, we need to be very careful to clean up after ourselves, to prevent
         * crashes and not being able to quit etc...
         * 
         * after the method is called, we call the eventbus to send a snapcapture event 
         * with our own media.
         */

        // new in 5.0.2: CameraFragment!
        String cameraFragment = (SNAPCHAT_VERSION < Obfuscator.FIVE_ZERO_TWO ? "CameraPreviewFragment" : "CameraFragment");
        findAndHookMethod("com.snapchat.android.camera." + cameraFragment, lpparam.classLoader, Obfuscator.CAMERA_LOAD.getValue(SNAPCHAT_VERSION), new XC_MethodHook() {

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if(initializedUri == null) return; // We don't have an image to send, so don't try to send one
                xposedDebug("Doing it's magic!");
                Object snapCaptureEvent;

                // new stuff for 4.1.10: Class called Snapbryo (gross)
                // this class now stores all the data for snaps. What's good for us is that we can continue using either a bitmap or a videouri in a method.
                // SnapCapturedEvent(Snapbryo(Builder(Media)))
                if(SNAPCHAT_VERSION >= Obfuscator.FOUR_ONE_TEN) {
                    Object builder = newInstance(findClass("com.snapchat.android.model.Snapbryo.Builder", lpparam.classLoader));
                    builder = callMethod(builder, Obfuscator.BUILDER_CONSTRUCTOR.getValue(SNAPCHAT_VERSION), media.getContent());
                    Object snapbryo = callMethod(builder, Obfuscator.CREATE_SNAPBRYO.getValue(SNAPCHAT_VERSION));
                    snapCaptureEvent = newInstance(SnapCapturedEventClass, snapbryo);
                }
                else {
                    snapCaptureEvent = newInstance(SnapCapturedEventClass, media.getContent());
                }

                Object busProvider = callStaticMethod(findClass("com.snapchat.android.util.eventbus.BusProvider", lpparam.classLoader), Obfuscator.GET_BUS.getValue(SNAPCHAT_VERSION));
                callMethod(busProvider, Obfuscator.BUS_POST.getValue(SNAPCHAT_VERSION), snapCaptureEvent);

                initializedUri = null; // clean up after ourselves. If we don't do this snapchat will crash.
            }
        });


        /**
         * Stop snapchat deleting our video when the view is cancelled.
         */
        findAndHookMethod("com.snapchat.android.SnapPreviewFragment", lpparam.classLoader, Obfuscator.ON_BACK_PRESS.getValue(SNAPCHAT_VERSION), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object thiz = param.thisObject;
                Uri tempFileUri = Uri.fromFile(File.createTempFile("delete", "me"));

                Object event;
                if(SNAPCHAT_VERSION < Obfuscator.FOUR_ONE_TEN) // make sure we dont delete video files on accident!
                    event = newInstance(SnapCapturedEventClass, tempFileUri);
                else {
                    Object builder = newInstance(findClass("com.snapchat.android.model.Snapbryo.Builder", lpparam.classLoader));
                    builder = callMethod(builder, Obfuscator.BUILDER_CONSTRUCTOR.getValue(SNAPCHAT_VERSION), tempFileUri);
                    event = newInstance(findClass("com.snapchat.android.model.Snapbryo", lpparam.classLoader), builder);
                }

                setObjectField(thiz, Obfuscator.M_SNAP_C_EVENT.getValue(SNAPCHAT_VERSION), event);
                xposedDebug("Prevented snapchat from deleting our video.");
            }
        });

    }

    /**
     * Write debug information to the Xposed Log if enabled in the settings
     * @param message The message you want to log
     * @param prefix Whether it should be prefixed by the log-tag
     */
    private void xposedDebug(String message, boolean prefix) {
        if (DEBUGGING) {
            if (prefix) {
                message = Commons.LOG_TAG + message;
            }
            XposedBridge.log(message);
        }
    }

    /**
     * Write debug information to the Xposed Log if enabled in the settings
     * This method always prefixes the message by the log-tag
     * @param message The message you want to log
     */
    private void xposedDebug(String message) {
        xposedDebug(message, true);
    }

    /**
     * Refreshes preferences
     */
    private void refreshPrefs() {
        prefs.reload();
        ROTATION_MODE = Integer.parseInt(prefs.getString("pref_rotation", Integer.toString(Commons.ROTATION_CW)));
        ADJUST_METHOD = Integer.parseInt(prefs.getString("pref_adjustment", Integer.toString(Commons.ADJUST_CROP)));
        DEBUGGING = prefs.getBoolean("pref_debug", false);
    }

    /** {@code XposedHelpers.callMethod()} cannot call methods of the super class of an object, because it
     * uses {@code getDeclaredMethods()}. So we have to implement this little helper, which should work
     * similar to {@code }callMethod()}. Furthermore, the exceptions from getMethod() are passed on.
     * <p>
     * At the moment, only argument-free methods supported (only case needed here). After a discussion
     * with the Xposed author it looks as if the functionality to call super methods will be implemented
     * in {@code XposedHelpers.callMethod()} in a future release.
     *
     * @param obj Object whose method should be called
     * @param methodName String representing the name of the argument-free method to be called
     * @return The object that the method call returns
     * @see <a href="http://forum.xda-developers.com/showpost.php?p=42598280&postcount=1753">
     *     Discussion about calls to super methods in Xposed's XDA thread</a>
     */
    private Object callSuperMethod(Object obj, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        return obj.getClass().getMethod(methodName).invoke(obj);
    }
}