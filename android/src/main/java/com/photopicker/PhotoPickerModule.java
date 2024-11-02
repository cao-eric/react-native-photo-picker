package com.photopicker;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.ContentResolver;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.io.File;
import java.io.InputStream;


public class PhotoPickerModule extends ReactContextBaseJavaModule {

    private static final int SINGLE_PHOTO_PICKER_REQUEST_CODE = 3;
    private static final int MULTIPLE_PHOTO_PICKER_REQUEST_CODE = 4;

    private Callback callback;

    private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
        @Override
        public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
            try {
                // Invalid request code
                boolean invalidRequestCode = (requestCode != SINGLE_PHOTO_PICKER_REQUEST_CODE && requestCode != MULTIPLE_PHOTO_PICKER_REQUEST_CODE);
                if (invalidRequestCode) {
                    return;
                }

                if (resultCode == RESULT_CANCELED) {
                    sendErrorToJS(PhotoPickerConstants.CANCELLED, PhotoPickerConstants.CANCEL_MESSAGE);
                    return;
                }

                if (intent == null) {
                    return;
                }
                
                WritableArray resultUris = Arguments.createArray();
                ContentResolver contentResolver = getReactApplicationContext().getContentResolver();

                boolean singleMedia = (intent.getData() != null); // user was restricted to selecting one media file only
                boolean multipleMedia = (intent.getClipData() != null); // user was able to multiselect media

                if (singleMedia) {
                    Uri uri = intent.getData();
                    WritableMap itemMap = getAttachmentData(contentResolver, uri);
                    resultUris.pushMap(itemMap);
                } else if (multipleMedia) {
                    ClipData clipData = intent.getClipData();
                    int count = clipData.getItemCount();
                    for (int i = 0; i < count; i++) {
                        ClipData.Item item = clipData.getItemAt(i);
                        Uri uri = item.getUri();

                        WritableMap itemMap = getAttachmentData(contentResolver, uri);
                        resultUris.pushMap(itemMap);
                    }
                }

                sendMessageToJS(PhotoPickerConstants.SUCCESS, resultUris);
            } catch (Exception e) {
                sendErrorToJS(PhotoPickerConstants.ERROR, e.toString());
            }
        }
    };

    PhotoPickerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(mActivityEventListener);
    }

    @NonNull
    @Override
    public String getName() {
        return PhotoPickerConstants.PACKAGE_NAME;
    }

    private void sendMessageToJS(String status, WritableArray data) {
        WritableMap params = Arguments.createMap();
        params.putString(PhotoPickerConstants.STATUS, status);
        params.putArray(PhotoPickerConstants.ATTACHMENTS, data);

        callback.invoke(params);
        callback = null;
    }

    private void sendErrorToJS(String status, String data) {
        WritableMap params = Arguments.createMap();
        params.putString(PhotoPickerConstants.STATUS, status);
        params.putString(PhotoPickerConstants.ERROR, data);

        callback.invoke(params);
        callback = null;
    }

    /**
    * Returns a WritableMap object containing the attachment data of the media file selected
    * @param uri of the file provided by the intent directly or through its clip data
    * @param cr ContentResolver used to access information such as mime type, size, etc
    * @return {uri, width, height, size, mime}
    */
    private WritableMap getAttachmentData(ContentResolver cr, Uri uri) {
        String mimeType = getMimeType(cr, uri);
        long fileSize = getFileSize(cr, uri);
        int[] dimensions = {0, 0}; //[width, height]

        if (mimeType.contains("image")) {
            dimensions = getImageDimensions(cr, uri);
        } else {
            dimensions = getVideoDimensions(uri);
        }

        // Create a WritableMap to return it to JS as an object
        WritableMap itemMap = Arguments.createMap();
        itemMap.putString(PhotoPickerConstants.URI, uri.toString());
        itemMap.putInt(PhotoPickerConstants.WIDTH, dimensions[0]);
        itemMap.putInt(PhotoPickerConstants.HEIGHT, dimensions[1]);
        itemMap.putDouble(PhotoPickerConstants.SIZE, (double) fileSize);
        itemMap.putString(PhotoPickerConstants.MIME, mimeType);

        return itemMap;
    }

    private String getMimeType(ContentResolver cr, Uri fileUri) {
        String mimeType = cr.getType(fileUri);
        return mimeType;
    }

    private long getFileSize(ContentResolver cr, Uri fileUri) {
        // Query the content resolver for a file type
        Cursor cursor = cr.query(fileUri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            long fileSize = cursor.getLong(sizeIndex);
            cursor.close();
            return fileSize;
        }

        return 0;
    }

    private int[] getImageDimensions(ContentResolver cr, Uri fileUri){
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            InputStream input = cr.openInputStream(fileUri);
            BitmapFactory.decodeStream(input, null, options);  input.close();
            return new int[]{options.outWidth, options.outHeight};
        }
        catch (Exception e){}
        return new int[]{0,0};
    }

    private int[] getVideoDimensions(Uri fileUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getReactApplicationContext(), fileUri);
            int width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            retriever.release();
            return new int[]{width, height};
        } catch (Exception e) {
            return new int[]{0,0};
        }
    }


    @ReactMethod
    public void launchPhotoPicker(ReadableMap params, Callback cb) {
        try {
            Activity currentActivity = getCurrentActivity();
            callback = cb;

            boolean multipleMedia = false;
            String mimeType = null;
            String mediaType = null;
            int maxItems = 0;

            if (params.hasKey("multipleMedia")) {
                multipleMedia = params.getBoolean("multipleMedia");
            }
            if (params.hasKey("mimeType")) {
                mimeType = params.getString("mimeType");
            }
            if (params.hasKey("mediaType")) {
                mediaType = params.getString("mediaType");
            }
            if (params.hasKey("maxItems")) {
                maxItems = params.getInt("maxItems");
            }

            int requestCode;
            Intent intent;

            PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
            PickVisualMediaRequest request = new PickVisualMediaRequest();

            if (mimeType != null) {
                request = builder.setMediaType(new ActivityResultContracts.PickVisualMedia.SingleMimeType(mimeType)).build();
            } else {
                if (mediaType != null) {
                    switch (mediaType) {
                        case PhotoPickerConstants.IMAGE_AND_VIDEO:
                            request = builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE).build();
                            break;
                        case PhotoPickerConstants.IMAGE_ONLY:
                            request = builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build();
                            break;
                        case PhotoPickerConstants.VIDEO_ONLY:
                            request = builder.setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE).build();
                            break;
                    }
                } else {
                    request = builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE).build();
                }
            }

            if (multipleMedia) {
                requestCode = MULTIPLE_PHOTO_PICKER_REQUEST_CODE;
                if (maxItems > 0) {
                    intent = new ActivityResultContracts.PickMultipleVisualMedia(maxItems).createIntent(getReactApplicationContext(), request);
                } else {
                    intent = new ActivityResultContracts.PickMultipleVisualMedia().createIntent(getReactApplicationContext(), request);
                }
            } else {
                requestCode = SINGLE_PHOTO_PICKER_REQUEST_CODE;
                intent = new ActivityResultContracts.PickVisualMedia().createIntent(getReactApplicationContext(), request);
            }
            currentActivity.startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            sendErrorToJS(PhotoPickerConstants.ERROR, e.toString());
        }
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put(PhotoPickerConstants.RN_SUCCESS, PhotoPickerConstants.SUCCESS);
        constants.put(PhotoPickerConstants.RN_ERROR, PhotoPickerConstants.ERROR);
        constants.put(PhotoPickerConstants.RN_CANCELLED, PhotoPickerConstants.CANCELLED);
        return constants;
    }
}