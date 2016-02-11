package com.example.android.sunshine.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Created by gabrielmarcos on 2/2/16.
 */
public class Utility {

    public interface LoadBitmapListener {
        void onError(String error);
        void onBitmapLoaded(Bitmap bitmap);
    }

    static public void loadBitmapFromAsset(final GoogleApiClient client, final Asset asset, final LoadBitmapListener listener) {

        if (asset == null) {
            listener.onError("Asset must not be null");
        }

        // The bitmap is loaded using an AsyncTask so it doesn't block the UI Thread
        new AsyncTask<Void,Void,Bitmap>() {

            @Override
            protected Bitmap doInBackground(Void... voids) {

                ConnectionResult result =
                        client.blockingConnect(2000, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }
                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        client, asset).await().getInputStream();
                client.disconnect();

                if (assetInputStream == null) {
                    return null;
                }

                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap == null) {
                    listener.onError("Null Bitmap");
                }else {
                    listener.onBitmapLoaded(bitmap);
                }
            }
        }.execute();
    }

}
