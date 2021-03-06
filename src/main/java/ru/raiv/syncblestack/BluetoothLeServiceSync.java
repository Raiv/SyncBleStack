package ru.raiv.syncblestack;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.raiv.syncblestack.tasks.BleAsyncTask;
import ru.raiv.syncblestack.tasks.BleOperation;
import ru.raiv.syncblestack.tasks.BleOperationFactory;
import ru.raiv.syncblestack.tasks.BleSyncTask;
import ru.raiv.syncblestack.tasks.BleTask;
import ru.raiv.syncblestack.tasks.BleTaskCompleteCallback;


/**
 * Service for managing connection and data communication with a GATT server
 * hosted on a given Bluetooth LE device.
 */
@SuppressLint("NewApi") public class BluetoothLeServiceSync extends Service {
    public final static String TAG = BluetoothLeServiceSync.class.getSimpleName();



    private static class BluetoothDeviceWrapper {
        volatile BluetoothGatt gatt;
        volatile long scanIteration = 0;
        volatile boolean isReady = false;
        volatile boolean autoReconnect = true;// default behaviour;
        volatile BluetoothDevice device = null;
    };

    // Device scan callback.
    private class LeScanCallback implements BluetoothAdapter.LeScanCallback {

        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            if(isScanning()) {// on Zuk z2 scan does not stops immediately?!?
            boolean notExists = true;
            boolean needUpdate = false;
            synchronized (gattSync) {

                for (BluetoothDeviceWrapper found : foundDevices) {
                    if (device.getAddress().equals(found.device.getAddress())) {
                            String devname = found.device.getName();
                            if ((devname == null) || !devname.equals(device.getName())) {
                                found.device = device;
                        }
                        if (found.scanIteration < scanIteration) {
                            found.scanIteration = scanIteration;
                            needUpdate = true;
                        }
                        notExists = false;

                        break;
                    }
                }
            }
            if (notExists) {
                needUpdate = true;
                BluetoothDeviceWrapper wrapper = new BluetoothDeviceWrapper();
                wrapper.device = device;
                wrapper.scanIteration = scanIteration;
                synchronized (gattSync) {
                    foundDevices.add(wrapper);
                }

                    Log.d(TAG, myNum() + "Le device added to processing");

            }
            if (needUpdate) {
                broadcastDeviceList();
            }
        }
        }
    };


    private static volatile int instanceNumCount =0;
    private volatile int instanceNum =0;

    private volatile BluetoothManager mBluetoothManager;
    private volatile BluetoothAdapter mBluetoothAdapter;
    private static final long SCAN_PERIOD = 30000;
    private volatile boolean mScanning = false;
    private volatile boolean continousScanning = true;
    private Handler mHandler;
    private final Queue<BleTask> taskQueue = new ConcurrentLinkedQueue<BleTask>();

    private final Object gattSync=new Object();
    private volatile BluetoothDeviceWrapper currentGatt=null;
    private final List<BluetoothDeviceWrapper> foundDevices=Collections.synchronizedList(new ArrayList<BluetoothDeviceWrapper>());
    private final List<BluetoothDeviceWrapper> prevFoundDevices=Collections.synchronizedList(new ArrayList<BluetoothDeviceWrapper>());
    private volatile LeScanCallback currentScan = null;


    @Override
    public void onCreate() {
        super.onCreate();
        instanceNumCount++;
        instanceNum=instanceNumCount;
        initialize();

    }

    private volatile long scanIteration = 0;

    @Override
    public String toString(){
        return instanceNum+" ";
    }

    public String myNum(){
        return instanceNum+" ";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        close();
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {


            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized (gattSync) {
                    if (gatt.getDevice().getAddress().equals(currentGatt.device.getAddress())) {
                        if(status==BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, myNum() + "Connected to GATT server.");
                            currentGatt.gatt = gatt;
                            currentGatt.gatt.discoverServices();
                        }else{
                            disconnectGatt(gatt);
                            broadcastGattError(gatt,status);
                        }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized (gattSync) {
                    if (gatt.equals(currentGatt.gatt)) {
                        if(!currentGatt.autoReconnect) {
                            gatt.close();
                            refreshDeviceCache(gatt);
                        }
                        currentGatt.gatt = null;
                        currentGatt.isReady=false;
                        broadcastDeviceState(BleConst.ACTION_DEVICE_DISCONNECTED);
                        if (inJob){
                            finishTask();
                        }
                        Log.i(TAG, myNum() + "Disconnected from GATT server.");
                    }
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            synchronized (gattSync) {
                if (gatt.equals(currentGatt.gatt) ) {
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        currentGatt.isReady=true;
                        broadcastDeviceState(BleConst.ACTION_DEVICE_CONNECTED);
                        doJob();
                    }else{
                        broadcastGattError(gatt,status);
                    }
                }else{
                  //  broadcastDeviceState(ACTION_DEVICE_CONNECTED);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG,myNum()+ characteristic.getUuid().toString()+" onCharacteristicRead status: " + status);
            if(gatt.equals(currentGatt.gatt)){
                if(status == BluetoothGatt.GATT_SUCCESS) {
                    finishRW(characteristic);
                }else{
                    broadcastGattError(gatt,status);
                    finishTask();
                }
            }
        }

        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG,myNum()+ characteristic.getUuid().toString()+" onCharacteristicWrite status: " + status);
            if(gatt.equals(currentGatt.gatt)){
                if(status == BluetoothGatt.GATT_SUCCESS) {
                    finishRW(characteristic);
                }else{
                    broadcastGattError(gatt,status);
                    finishTask();
                }
            }

        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG,myNum()+ characteristic.getUuid().toString()+" onCharacteristicChange");
            if(gatt.equals(currentGatt.gatt)){
                BleOperation operation = BleOperationFactory.getListenOperation(characteristic.getService().getUuid(),characteristic.getUuid());
                operation.setValue(characteristic.getValue());
                operation.setSucceed(true);
                broadcastCharacteristicNotification(operation);
            }
        }



        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            if(gatt.equals(currentGatt.gatt)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    finishNotification();
                } else {
                    broadcastGattError(gatt,status);
                    finishTask();
                }
            }
        }
    };
    private ExecutorService syncTaskExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService disconnectExecutor = Executors.newSingleThreadExecutor();
    void addTask(BleTask task){
        synchronized (gattSync){
            if(currentGatt!=null && currentGatt.gatt!=null && currentGatt.isReady){
                taskQueue.add(task);
                if(task.isSync()) {
                   // BleSyncTask bst = (BleSyncTask)task;
                    syncTaskExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            doJob();
                        }
                    });
                }else{
                    doJob();
                }

            }else{
                while(task.hasNext()){
                    BleOperation op = task.next();
                    op.setSucceed(false);
                }
                if((!task.isSync())&&(task instanceof BleAsyncTask)){
                    final BleAsyncTask asyncTask=((BleAsyncTask) task);
                    final BleTaskCompleteCallback cb =asyncTask.getCallback();
                    if(cb!=null) {
                        ((BleAsyncTask) task).callbackHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                    cb.onTaskComplete(asyncTask);
                            }
                        });
                    }
                }
                return;
            }
        }
        if(task.isSync()) {
            BleSyncTask bst = (BleSyncTask) task;
            synchronized (bst.getSyncObject()) {
                try {
                    bst.getSyncObject().wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    void disconnectDevice(String deviceAddress){
        synchronized (gattSync){
            if(currentGatt!=null && currentGatt.device!=null && currentGatt.device.getAddress().equals(deviceAddress)) {
                close();
            }
        }
    }



    @Nullable
    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt gatt, BleOperation operation){
        BluetoothGattService service =gatt.getService(operation.getService());
        if(service!=null){
            return service.getCharacteristic(operation.getCharacteristic());
        }
        return null;
    }


    private void finishTask(){
        inJob=false;
        synchronized (gattSync) {
            BleTask task=taskQueue.poll();
            task.reset();
            if(task instanceof BleSyncTask){
                BleSyncTask syncTask = (BleSyncTask)task;
                synchronized (syncTask.getSyncObject()){
                    syncTask.getSyncObject().notifyAll();
                }
            }else if(task instanceof BleAsyncTask){
                final BleAsyncTask asyncTask = (BleAsyncTask)task;
                Handler callbackHandler = asyncTask.callbackHandler();
                if(callbackHandler==null && asyncTask.getCallback()!=null){
                    callbackHandler=mHandler;
                }
                if(callbackHandler!=null) {
                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            BleTaskCompleteCallback callback = asyncTask.getCallback();
                            if (callback != null) {
                                callback.onTaskComplete(asyncTask);
                            }
                        }
                    });
                }
            }
        }
        doJob();
    }


    private void finishRW(BluetoothGattCharacteristic characteristic){
        BleTask task = null;
        synchronized (gattSync) {
            task = taskQueue.peek();
        }
        if(task!=null){
            final BleOperation operation =task.current();
            operation.setValue(characteristic.getValue());
            operation.setSucceed(true);
            finishOperation(task);
        }

    }

    private void finishNotification(){
        BleTask task = null;
        synchronized (gattSync) {
            task = taskQueue.peek();
        }
        if(task!=null){
            final BleOperation operation =task.current();
            operation.setSucceed(true);
            finishOperation(task);
        }

    }

    private void finishOperation(BleTask task){
        if(task.hasNext()){
            inJob=false;
            doJob();
        }else{
            finishTask();
        }
    }

    private volatile boolean inJob=false;

    private void doJob(){
        if(inJob){
            return;
        }
        boolean queueEmpty;
        synchronized (gattSync) {
            queueEmpty = taskQueue.isEmpty();
        }
        if(queueEmpty){
            stopSelfIfNeeded();
        }else{
            BleTask task = null;
            boolean check = false;
            synchronized (gattSync) {
                task = taskQueue.peek();
                if (currentGatt.gatt != null && currentGatt.isReady && task != null && task.hasNext()) {
                    BleOperation operation = task.next();
                    BluetoothGattCharacteristic characteristic = findCharacteristic(currentGatt.gatt, operation);
                    if (characteristic == null) {
                        // return
                        operation.setSucceed(false);
                        finishTask();
                        return;
                    }
                    switch (operation.getOpType()) {
                        case READ:
                            currentGatt.gatt.readCharacteristic(characteristic);
                            break;
                        case WRITE_NO_RESPONSE:
                            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

                        case WRITE:
                            characteristic.setValue(operation.getValue());
                            currentGatt.gatt.writeCharacteristic(characteristic);
                            break;
                        case CHECK:
                            operation.setSucceed(true);
                            check=true;
                            break;
                        case LISTEN:
                            currentGatt.gatt.setCharacteristicNotification(characteristic,true);
                            UUID uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
                            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(uuid);
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            currentGatt.gatt.writeDescriptor(descriptor);
                            break;
                    }

                }
            }
            if(check) {
                finishOperation(task);
            }else{
                inJob=true;
            }
        }
    }

    private void broadcastScanFinish(){
        Intent i = new Intent( BleConst.ACTION_SEARCH_FINISHED);
        sendBroadcast(i);
    }

    private void broadcastCharacteristicNotification(BleOperation operation){
        Intent i = new Intent( BleConst.ACTION_CHARACTERISTIC_NOTIFICATION);
        i.putExtra(BleConst.PARAM_CHARACTERISTIC_NOTIFICATION,operation);
        synchronized (gattSync) {
            BleDeviceInfo info = new BleDeviceInfo(currentGatt.device.getName(),currentGatt.device.getAddress());
            i.putExtra(BleConst.PARAM_DEVICE_NAME,info );
        }

        sendBroadcast(i);
    }

    private void broadcastDeviceState(String action){
        Intent i = new Intent(action);
        synchronized (gattSync) {
            BleDeviceInfo info = new BleDeviceInfo(currentGatt.device.getName(),currentGatt.device.getAddress());
            i.putExtra(BleConst.PARAM_DEVICE_NAME,info );
        }
        sendBroadcast(i);
    }
    private void broadcastGattError(BluetoothGatt gatt,int status){

        synchronized (gattSync) {
            //if(currentGatt.gatt==gatt) {
                Intent i = new Intent(BleConst.ACTION_DEVICE_ERROR);
                BleDeviceInfo info = new BleDeviceInfo(currentGatt.device.getName(), currentGatt.device.getAddress());
                i.putExtra(BleConst.PARAM_DEVICE_NAME, info);
                i.putExtra(BleConst.PARAM_DEVICE_ERROR, status);
                sendBroadcast(i);
           // }
        }

    }
    private void broadcastDeviceList(){
        Intent i = new Intent(BleConst.ACTION_DEVICES_FOUND);

        ArrayList<BleDeviceInfo> devices = new ArrayList<>();
        //boolean hasDevices = false;
        synchronized (gattSync){
          //  hasDevices=foundDevices.size()>0;
            for(BluetoothDeviceWrapper wrapper:foundDevices){
                BleDeviceInfo info = new BleDeviceInfo(wrapper.device.getName(),wrapper.device.getAddress());
                devices.add(info);
            }
        }
        //if(hasDevices) {
            i.putExtra(BleConst.PARAM_DEVICES_FOUND_LIST, devices.toArray(new BleDeviceInfo[devices.size()]));
            sendBroadcast(i);
        //}
    }

    @Override
    public IBinder onBind(Intent intent) {
        bound = true;
        return mBinder;
    }


    private volatile boolean bound =false;


    public void stopSelfIfNeeded(){
        if(bound) return;
        synchronized (taskQueue) {
            if(taskQueue.isEmpty()) {
                close();
                setScanning(false,false);
                stopSelf();
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {

        bound = false;
        stopSelfIfNeeded();
        return false;
    }

    private final IBinder mBinder = new BleBinder(this);


    public boolean initialize() {

        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG,myNum()+ "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, myNum()+"Unable to obtain a BluetoothAdapter.");
            return false;
        }
        mHandler = new Handler(Looper.getMainLooper());// to ensure it runs on UI thread

        return true;
    }

    public boolean connect(final String address, boolean reconnect) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG,myNum()+ "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }
        if(currentGatt!= null&&currentGatt.gatt!=null&&currentGatt.device!=null &&currentGatt.device.getAddress().equals(address)){
            return false;
        }


        BluetoothDevice device =mBluetoothAdapter.getRemoteDevice(address);
        //if(device==null){
            synchronized (gattSync) {
                 for(BluetoothDeviceWrapper bdw: foundDevices){
                     if(bdw.device!=null&& bdw.device.getAddress().equals(address)){
                         currentGatt=bdw;
                         device=bdw.device;
                         currentGatt.autoReconnect=reconnect;
                         break;
                     }
                 }
            }
        //}
        if(device==null){
        synchronized (gattSync) {
            for(BluetoothDeviceWrapper bdw: prevFoundDevices){
                if(bdw.device!=null&& bdw.device.getAddress().equals(address)){
                    currentGatt=bdw;
                    device =bdw.device;
                    currentGatt.autoReconnect=reconnect;
                    break;
                }
            }
        }
        }

        if (device == null) {
            Log.w(TAG, myNum()+"Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the
        // autoConnect
        // parameter to false.
        synchronized (gattSync) {
            if(currentGatt==null){
                currentGatt=new BluetoothDeviceWrapper();

            }
            currentGatt.isReady=false;
            currentGatt.device=device;
            synchronized (disconnectSync) {
                currentGatt.gatt = device.connectGatt(this, false, mGattCallback);
            }

        }

        Log.d(TAG, myNum()+"Trying to create a new connection.");
        return true;
    }

    private final Object disconnectSync = new Object();

    private void disconnectGatt(final BluetoothGatt gatt){

        gatt.disconnect();
        disconnectExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        synchronized (disconnectSync) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }

                            gatt.close();
                        }
                        refreshDeviceCache(gatt);
                    }
                }
        );
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The
     * disconnection result is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        synchronized (gattSync) {
            if (currentGatt!=null &&currentGatt.gatt != null) {

               // fix for https://issuetracker.google.com/37057260
                disconnectGatt(currentGatt.gatt);
                //currentGatt.gatt = null;
                currentGatt.isReady=false;
            }
        }
    }

    /**
     * After using a given BLE device, the app must call this method to ensure
     * resources are released properly.
     */
    public void close() {

        if (isScanning()) {
            setScanning(false,false);
        }
       disconnect();
    }

    public boolean isScanning() {
        return mScanning;
    }


    public void resetCardsList() {

            synchronized (gattSync) {
                prevFoundDevices.clear();
                prevFoundDevices.addAll(foundDevices);
                foundDevices.clear();
            }
    }

    public void reconnect(){
        synchronized(gattSync) {
            if (currentGatt != null && currentGatt.device != null && !currentGatt.isReady) {
                connect(currentGatt.device.getAddress(),currentGatt.autoReconnect);
            }
        }
    }


    private Runnable stopScanRunnable = new Runnable() {
        @SuppressWarnings("deprecation")
        @Override
        public void run() {

            synchronized (gattSync) {
                if(currentScan == null)
                {
                    return;
                }
                mBluetoothAdapter.stopLeScan(currentScan);
                broadcastDeviceList();
              //  resetCardsList();
                if(continousScanning) {
                    scanIteration++;
                    currentScan = new LeScanCallback();
                    mBluetoothAdapter.startLeScan(currentScan);
                    mHandler.postDelayed(stopScanRunnable, SCAN_PERIOD);
                }else{
                    mScanning=false;
                    currentScan=null;
                    broadcastScanFinish();
                    resetCardsList();
                }
            }
        }
    };



    @SuppressWarnings("deprecation")
    public void setScanning(boolean enable, boolean continous) {
        continousScanning=continous;
        if (mScanning == enable)
            return;
        if (mBluetoothAdapter == null) {
            mScanning = false;
            return;
        }
        mScanning = enable;
        synchronized(gattSync){
            if (enable) {
                // Stops scanning after a pre-defined scan period.
                mHandler.postDelayed(stopScanRunnable, SCAN_PERIOD);
                scanIteration++;
                if(currentScan !=null)
                {
                    throw new RuntimeException("Something dublicates scans!!!");
                }
                currentScan =new LeScanCallback();
                mBluetoothAdapter.startLeScan(currentScan);
            } else {
                mHandler.removeCallbacks(stopScanRunnable);
                mBluetoothAdapter.stopLeScan(currentScan);
                currentScan = null;
                //broadcastDeviceList();
                resetCardsList();
            }
        }
    }

    public boolean refreshCurrentGatt(){
        synchronized (gattSync) {
            if (currentGatt != null && currentGatt.gatt != null) {
                return refreshDeviceCache(currentGatt.gatt);
            }
        }
        return false;
    }


    private boolean refreshDeviceCache(BluetoothGatt gatt){
        try {
            final BluetoothGatt localBluetoothGatt = gatt;
            Class<?> localClass = localBluetoothGatt.getClass();
            Method[] all = localClass.getDeclaredMethods();
            Method[] pubMethods = localClass.getMethods();
            Method localMethod = localClass.getMethod("refresh");

            if (localMethod != null) {
                Log.d(TAG,"refresh found");
                boolean bool = (Boolean) localMethod.invoke(localBluetoothGatt);
                Log.d(TAG, "refresh status:"+bool);
                return bool;
            }else{
                Log.d(TAG,"refresh not found! declared methods:\n"+ Arrays.deepToString(all)+"\n public methods:\n"+Arrays.deepToString(pubMethods));
            }
        }
        catch (Exception localException) {
            Log.e(TAG, "An exception occured while refreshing device");
        }
        return false;
    }
}