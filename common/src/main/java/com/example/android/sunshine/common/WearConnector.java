package com.example.android.sunshine.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Created by gabrielmarcos on 2/11/16.
 */
public class WearConnector implements DataApi.DataListener,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    private static final String TAG = "WEAR CONNECTOR";

    private static final String DATA_PATH = "/sunshine_weather";
    private static final String WEATHER_HIGH_PATH = "sunshine_weather_high";
    private static final String WEATHER_LOW_PATH = "sunshine_weather_low";
    private static final String WEATHER_ICON_PATH = "sunshine_icon";

    public interface ConnectionInterface {
        void onConnected();
        void onError(String error);
    }

    public interface SunshineDataInterface {
        void onDataChanged(String highTemp, String lowTemp, Bitmap bitmap);
        void onError(String error);
    }

    private ConnectionInterface mConnectionInterface;
    private SunshineDataInterface mDataInterface;

    private Context mContext;
    private GoogleApiClient mGoogleApiClient;

    public WearConnector(Context context) {
        this.mContext = context;

         this.mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

    }

    public void connect(ConnectionInterface connectionInterface) {

        this.mConnectionInterface = connectionInterface;

        if (mGoogleApiClient != null) {
            mGoogleApiClient.connect();
        }
    }

    public void disconnect() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
        }
    }

    public void startListeningForData(SunshineDataInterface dataInterface) {

        this.mDataInterface = dataInterface;

        if (mGoogleApiClient != null) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        mConnectionInterface.onConnected();

        Log.d(TAG,"onConnected Called");
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG,"onConnectionSuspended Called");
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {

        Log.d(TAG,"onDataChanged Called");

        for (DataEvent event : dataEventBuffer) {

            DataItem item = event.getDataItem();

            // We only consider Data Changed events
            if (DATA_PATH.equals(item.getUri().getPath()) && event.getType() == DataEvent.TYPE_CHANGED) {

                DataMap map = DataMapItem.fromDataItem(item).getDataMap();
                final String mHighTemperature = map.getString(WEATHER_HIGH_PATH);
                final String mLowTemperature = map.getString(WEATHER_LOW_PATH);
                Asset iconAsset = map.getAsset(WEATHER_ICON_PATH);

                Utility.loadBitmapFromAsset(mGoogleApiClient, iconAsset, new Utility.LoadBitmapListener() {
                    @Override
                    public void onError(String error) {

                    }

                    @Override
                    public void onBitmapLoaded(Bitmap bitmap) {
                        mDataInterface.onDataChanged(mHighTemperature, mLowTemperature, bitmap);
                    }
                });
            }
        }
    }

    /**
     * Sends the Data using the Wereable DataApi
     * @param highTemp
     * @param lowTemp
     * @param weatherIcon
     */
    public void sendData(String highTemp, String lowTemp, Bitmap weatherIcon) {

        Asset assetIcon = Utility.createAssetFromBitmap(weatherIcon);

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(DATA_PATH);
        putDataMapReq.getDataMap().putString(WEATHER_HIGH_PATH, highTemp);
        putDataMapReq.getDataMap().putString(WEATHER_LOW_PATH, lowTemp);
        putDataMapReq.getDataMap().putAsset(WEATHER_ICON_PATH,assetIcon);

        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
            @Override
            public void onResult(DataApi.DataItemResult dataItemResult) {
                if (dataItemResult.getStatus().isSuccess()) {
                    Log.d(TAG,"on Message Sent");
                }else {
                    Log.d(TAG,"Error sending message to wereable");
                }
            }
        });
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        mConnectionInterface.onError("Connection Failed");
    }
}
