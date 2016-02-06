package com.example.evan.scout;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ConnectThread extends Thread {
    protected static BluetoothDevice device = null;
    protected static final Object deviceLock = new Object();
    protected MainActivity context;
    protected String superName;
    protected String uuid;
    private Map<String, String> dataPoints;



    public ConnectThread(MainActivity context, String superName, String uuid, String matchName, String data) {
        this.context = context;
        this.superName = superName;
        this.uuid = uuid;
        dataPoints = new HashMap<>();
        dataPoints.put(matchName.replaceAll("UNSENT_", ""), data);
    }

    public ConnectThread(MainActivity context, String superName, String uuid, Map<String, String> dataPoints) {
        this.context = context;
        this.superName = superName;
        this.uuid = uuid;
        this.dataPoints = new HashMap<>();
        for (Map.Entry<String, String> entry : dataPoints.entrySet()) {
            this.dataPoints.put(entry.getKey().replaceFirst("UNSENT_", ""), entry.getValue());
        }
    }




    public static boolean initBluetooth(final Activity context, String superName) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.wtf("Bluetooth Error", "Device Not Configured With Bluetooth");
            toastText("Device Not Configured With Bluetooth", Toast.LENGTH_LONG, context);
            return false;
        }
        if (!adapter.isEnabled()) {
            Log.e("Bluetooth Error", "Bluetooth Not Enabled");
            toastText("Bluetooth Not Enabled", Toast.LENGTH_LONG, context);
            return false;
        }
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        if (devices.size() < 1) {
            Log.e("Bluetooth Error", "No Paired Devices");
            toastText("No Paired Devices", Toast.LENGTH_LONG, context);
            return false;
        }
        adapter.cancelDiscovery();
        for (BluetoothDevice tmpDevice : devices) {
            if (tmpDevice.getName().equals(superName)) {
                synchronized (deviceLock) {
                    device = tmpDevice;
                }
                return true;
            }
        }
        Log.e("Bluetooth Error", "No Paired Device With Name: \"" + superName + "\"");
        toastText("No Paired Device With Name: \"" + superName + "\"", Toast.LENGTH_LONG, context);
        return false;
    }



    @Override
    public void run() {
        //first we save to a file so if something goes wrong we have backups.  We use external storage so it is not deleted when app is reinstalled.
        //storage path: /sdcard/Android/MatchData
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            Log.e("File Error", "External Storage not Mounted");
            toastText("External Storage Not Mounted", Toast.LENGTH_LONG, context);
            return;
        }

        List<File> files = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        for (Map.Entry<String, String> entry : dataPoints.entrySet()) {
            File dir;
            File file;
            PrintWriter fileWriter;
            try {
                dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/MatchData");
                if (!dir.mkdir()) {
                    Log.i("File Info", "Failed to make Directory.  Unimportant");
                }
                //we first name the file with the prefix "UNSENT_".  If all goes well, it is renamed without the prefix, but if something fails it will still have it.
                file = new File(dir, "UNSENT_" + entry.getKey());
                fileWriter = new PrintWriter(file);
            } catch (IOException ioe) {
                Log.e("File Error", "Failed to open file");
                toastText("Failed To Open File", Toast.LENGTH_LONG, context);
                return;
            }


            fileWriter.print(entry.getValue());
            fileWriter.close();
            if (fileWriter.checkError()) {
                Log.e("File Error", "Failed to Write to File");
                toastText("Failed To Save Match Data To File", Toast.LENGTH_LONG, context);
                return;
            }
            dirs.add(dir);
            files.add(file);
        }



        if(!initBluetooth(context, superName)) {
            return;
        }



        PrintWriter out;
        BufferedReader in;
        BluetoothSocket socket;
        int counter = 0;
        //we loop until a connection is made
        while (true) {
            try {
                synchronized (deviceLock) {
                    socket = device.createRfcommSocketToServiceRecord(UUID.fromString(uuid)); // make this a constant somewhere nice
                }
                Log.i("Socket Info", "Attempting To Start Connection...");
                socket.connect();
                Log.i("Socket info", "Connection Successful!  Getting Ready To Send Data...");
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                break;
            } catch (IOException ioe) {
                Log.e("Socket Error", "Failed To Open Socket");
                //toastText("Failed To Connect To Super", Toast.LENGTH_SHORT, context);
                counter++;
                //we try three times before giving up
                if (counter == 3) {
                    Log.e("Socket Error", "Repeated Socket Open Failure");
                    context.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(context)
                                    .setTitle("Repeated Connection Failure")
                                    .setMessage("Please resend this data when successful data transfer is made.")
                                    .setNeutralButton("Dismiss", null)
                                    .show();
                        }
                    });
                    return;
                }
            }
        }



        String data = "";
        for (Map.Entry<String, String> entry : dataPoints.entrySet()) {
            data = data.concat(entry.getValue() + "\n");
        }
        Log.i("Communications Info", "Starting To Communicate");
        counter = 0;
        //we loop until the data is sent without io error or error code from super
        while (true) {
            try {
                Log.i("JSON during send", data);
                //we print the length of the data before we print the data so the super can identify corrupted data
                out.println(data.length());
                out.print(data);
                //we print '\0' at end of data to signify the end
                out.println("\0");
                out.flush();
                if (out.checkError()) {
                    throw new IOException();
                }
                int ackCode = Integer.parseInt(in.readLine());
                //super will send 0 if the data sizes match up, 1 if they don't
                if (ackCode == 1) {
                    throw new IOException();
                } else if (ackCode == 2) {
                    //data is invalid JSON, notify user and return
                    Log.e("Communications Error", "Data not in valid format");
                    Toast.makeText(context, "Data not in valid format", Toast.LENGTH_LONG).show();
                    return;
                }
                break;
            } catch (IOException ioe) {
                Log.e("Communications Error", "Failed To Send Data");
                //toastText("Failed To Send Match Data To Super", Toast.LENGTH_SHORT, context);
                counter++;
                //after the third failure, we terminate thread and notify user
                if (counter == 3) {
                    Log.e("Communications Error", "Repeated Data Send Failure");
                    context.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(context)
                                    .setTitle("Repeated Data Send Failure")
                                    .setMessage("Please resend this data when successful data transfer is made.")
                                    .setNeutralButton("Dismiss", null)
                                    .show();
                        }
                    });
                    return;
                }
            }
        }




        Log.i("Communications Info", "Done");
        toastText("Data Send Success", Toast.LENGTH_LONG, context);
        try {
            int i = 0;
            for (Map.Entry<String, String> entry : dataPoints.entrySet()) {
                if (!files.get(i).renameTo(new File(dirs.get(i), entry.getKey()))) {
                    Log.e("File Error", "Failed to Rename File");
                }
                i++;
            }
            in.close();
            out.close();
            socket.close();
        } catch (IOException ioe) {
            Log.e("Socket Error", "Failed To End Socket");
            toastText("Failed To Close Connection To Super", Toast.LENGTH_LONG, context);
        }
    }



    protected static void toastText(final String text, final int duration, final Activity context) {
        context.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, text, duration).show();
            }
        });
    }
}
