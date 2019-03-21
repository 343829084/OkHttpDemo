package com.example.administrator.okhttpdemo.get;

import android.util.Log;

import java.io.IOException;
import java.util.LinkedList;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Client {

    private static Client client;
    private  OkHttpClient okHttpClient;

    public Client() {
        okHttpClient = new OkHttpClient();
    }

    public static Client getInstance(){
        if (client == null){
           client = new Client();
        }
        return client;
    }

    public void testGet(String url){
      new Thread(new Runnable() {
          @Override
          public void run() {
              Request request = new Request.Builder().url(url).method("Get",null).build();
                  Call call = okHttpClient.newCall(request);
                  call.enqueue(new Callback() {
                      @Override
                      public void onFailure(Call call, IOException e) {

                      }

                      @Override
                      public void onResponse(Call call, Response response) throws IOException {
                          if (response != null) {
                              Log.d("lei", response.toString());
                          }
                      }
                  });
          }
      }).start();
    }
}
