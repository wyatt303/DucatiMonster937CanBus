package pl.linuch.ducatitelemetry

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    companion object { private const val REQUEST_CORE=100; private const val REQUEST_LOCATION=101; private const val CREATE_CSV=200; private const val CREATE_GPX=201; private const val CREATE_BOTH=202; private const val OPEN_FIRMWARE=300 }
    private lateinit var status:TextView; private lateinit var rpm:TextView; private lateinit var gear:TextView; private lateinit var speed:TextView
    private lateinit var throttle:TextView; private lateinit var brake:TextView; private lateinit var engine:TextView; private lateinit var ambient:TextView
    private lateinit var packets:TextView; private lateinit var deviceInfo:TextView; private lateinit var sessionStatus:TextView
    private lateinit var connect:Button; private lateinit var record:Button; private lateinit var stopRecording:Button; private lateinit var updateFirmware:Button
    private lateinit var savedRides:LinearLayout; private lateinit var retention:Spinner; private lateinit var gnssSwitch:Switch; private lateinit var leanSwitch:Switch
    private lateinit var gnssStatusView:TextView; private lateinit var leanStatusView:TextView; private lateinit var calibrateLean:Button; private lateinit var resetLean:Button
    private val csvExporter=CsvExporter(); private val gpxExporter=GpxExporter(); private var service:RideRecordingService?=null; private var bound=false; private var rendering=false
    private var currentState=RideServiceSnapshot(); private var pendingExportSessionId:String?=null; private var lastMessage:String?=null
    private val observer:(RideServiceSnapshot)->Unit={ s->runOnUiThread{render(s)} }
    private val connection=object:ServiceConnection{
        override fun onServiceConnected(name:ComponentName?, binder:IBinder?){ service=(binder as RideRecordingService.LocalBinder).service(); bound=true; service!!.observe(observer); configureRetentionSelection() }
        override fun onServiceDisconnected(name:ComponentName?){ bound=false; service=null; record.isEnabled=false; connect.isEnabled=false }
    }
    override fun onCreate(savedInstanceState:Bundle?){ super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); bindViews(); configureUi(); requestCorePermissionsIfNeeded() }
    override fun onStart(){ super.onStart(); bindService(Intent(this,RideRecordingService::class.java),connection,BIND_AUTO_CREATE) }
    override fun onStop(){ if(bound){service?.removeObserver(observer);unbindService(connection);bound=false};super.onStop() }

    private fun bindViews(){
        status=findViewById(R.id.connectionStatus);rpm=findViewById(R.id.rpmValue);gear=findViewById(R.id.gearValue);speed=findViewById(R.id.speedValue)
        throttle=findViewById(R.id.throttleValue);brake=findViewById(R.id.brakeValue);engine=findViewById(R.id.engineTempValue);ambient=findViewById(R.id.ambientTempValue)
        packets=findViewById(R.id.packetInfo);deviceInfo=findViewById(R.id.deviceInfo);sessionStatus=findViewById(R.id.sessionStatus);connect=findViewById(R.id.connectButton)
        record=findViewById(R.id.recordButton);stopRecording=findViewById(R.id.stopRecordingButton);updateFirmware=findViewById(R.id.updateFirmwareButton)
        savedRides=findViewById(R.id.savedRidesContainer);retention=findViewById(R.id.retentionSpinner);gnssSwitch=findViewById(R.id.gnssSwitch);leanSwitch=findViewById(R.id.leanSwitch)
        gnssStatusView=findViewById(R.id.gnssStatus);leanStatusView=findViewById(R.id.leanStatus);calibrateLean=findViewById(R.id.calibrateLeanButton);resetLean=findViewById(R.id.resetLeanButton)
    }
    private fun configureUi(){
        connect.isEnabled=false;record.isEnabled=false
        connect.setOnClickListener{val r=service?:return@setOnClickListener;if(currentState.bleState!=BleConnectionState.DISCONNECTED)r.disconnect()else if(hasBlePermissions())r.connect()else requestCorePermissionsIfNeeded()}
        record.setOnClickListener{try{val r=service?:return@setOnClickListener;when(currentState.session?.state){null->r.startRide();RideSessionState.RECORDING->r.pauseRide();RideSessionState.PAUSED->r.resumeRide();else->Unit}}catch(e:Exception){showError(e)}}
        stopRecording.setOnClickListener{try{if(service?.stopRide()==null)Toast.makeText(this,"Empty ride discarded",Toast.LENGTH_SHORT).show();renderSavedRides()}catch(e:Exception){showError(e)}}
        updateFirmware.setOnClickListener{selectFirmware()}
        gnssSwitch.setOnCheckedChangeListener{_,v->if(!rendering){if(v&&!hasLocationPermission())requestLocationPermission();service?.setPhoneSensors(v,leanSwitch.isChecked)}}
        leanSwitch.setOnCheckedChangeListener{_,v->if(!rendering)service?.setPhoneSensors(gnssSwitch.isChecked,v)}
        calibrateLean.setOnClickListener{AlertDialog.Builder(this).setTitle("Lean Angle Calibration").setMessage("Place the motorcycle upright and stationary with the phone mounted in its normal riding position.").setNegativeButton("Cancel",null).setPositiveButton("Calibrate"){_,_->val ok=service?.calibrateLean()==true;Toast.makeText(this,if(ok)"Lean angle calibrated: upright is 0°" else "Waiting for orientation sensor",Toast.LENGTH_LONG).show()}.show()}
        resetLean.setOnClickListener{service?.resetLean();Toast.makeText(this,"Lean calibration reset",Toast.LENGTH_SHORT).show()}
        retention.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("5","10","20","50","Unlimited"))
        retention.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){if(!rendering)service?.setRetention(when(pos){0->5;1->10;2->20;3->50;else->null});renderSavedRides()};override fun onNothingSelected(p:AdapterView<*>?)=Unit}
    }
    private fun configureRetentionSelection(){rendering=true;retention.setSelection(when(service?.retentionLimit()){5->0;20->2;50->3;null->4;else->1});rendering=false;renderSavedRides();if(currentState.gnssEnabled&&!hasLocationPermission())requestLocationPermission()}

    private fun render(s:RideServiceSnapshot){
        currentState=s;rendering=true;status.text=s.connectionMessage;connect.isEnabled=hasBlePermissions();connect.text=when(s.bleState){BleConnectionState.CONNECTED->"Disconnect";BleConnectionState.RECONNECTING->"Cancel reconnect";BleConnectionState.CONNECTING->"Cancel";BleConnectionState.DISCONNECTED->"Connect"}
        s.telemetry?.let{t->rpm.text=t.rpm.toString();gear.text="Gear ${if(t.gear==0)"N" else t.gear}";speed.text="Speed %.2f km/h".format(Locale.US,t.speedKmh);throttle.text="Throttle %.2f %%".format(Locale.US,t.throttlePercent);brake.text="Front brake %.2f %%".format(Locale.US,t.frontBrakePercent);engine.text="Engine ${t.engineTempC} °C";ambient.text="Ambient ${t.ambientTempC} °C"}
        packets.text="Packets ${s.packetCount}   Sequence ${s.telemetry?.sequence?:0}   Dropped ${s.droppedPackets}"
        deviceInfo.text=s.deviceInfo?.let{"Firmware: v${it.firmware}\nProtocol: ${it.protocol}\nBuild: ${it.build}"}?:"Firmware: Unknown"
        gnssSwitch.isChecked=s.gnssEnabled;leanSwitch.isChecked=s.leanEnabled&&s.leanAvailable;leanSwitch.isEnabled=s.leanAvailable;calibrateLean.isEnabled=s.leanEnabled&&s.leanAvailable;resetLean.isEnabled=s.calibrated
        gnssStatusView.text="GPS: ${label(s.gnssStatus)}"+(s.phoneSensors.gnss?.accuracyM?.let{"\nAccuracy: %.1f m".format(Locale.US,it)}?:"")
        leanStatusView.text=when{!s.leanEnabled->"Lean: Disabled";!s.leanAvailable->"Lean: unavailable (orientation sensor missing)";!s.calibrated->"Lean calibration: Required";s.phoneSensors.imu!=null->"Lean: %+.1f°\nIMU: %s".format(Locale.US,s.phoneSensors.imu.leanAngleDeg,accuracyLabel(s.phoneSensors.imu.accuracy));else->"Lean calibration: Calibrated · Waiting for IMU"}
        renderSession(s.session,s.bleState);updateFirmware.isEnabled=s.bleState==BleConnectionState.CONNECTED&&!s.otaInProgress;rendering=false
        s.message?.takeIf{it!=lastMessage}?.let{Toast.makeText(this,it,Toast.LENGTH_LONG).show();lastMessage=it}
    }
    private fun renderSession(session:RideSession?,ble:BleConnectionState){
        when(session?.state){RideSessionState.RECORDING->{record.text="Pause";record.isEnabled=true;stopRecording.visibility=View.VISIBLE};RideSessionState.PAUSED->{record.text="Resume";record.isEnabled=true;stopRecording.visibility=View.VISIBLE};else->{record.text="Start";record.isEnabled=ble==BleConnectionState.CONNECTED;stopRecording.visibility=View.GONE}}
        sessionStatus.text=if(session==null)"Recording: Idle" else {val state=if(session.state==RideSessionState.PAUSED)"Paused" else "Recording";"$state in foreground service · ${session.sampleCount} samples\n${bikeLabel(ble)}\nTotal ${duration(session.totalDurationMs())} · Paused ${duration(session.pausedDurationMs())} · Recorded ${duration(session.activeDurationMs())}"}
    }
    private fun renderSavedRides(){val r=service?:return;savedRides.removeAllViews();val rides=r.listSessions();if(rides.isEmpty()){savedRides.addView(TextView(this).apply{text="No saved rides";setTextColor(getColor(R.color.text_secondary))});return};val dates=SimpleDateFormat("dd MMM yyyy HH:mm",Locale.getDefault());rides.forEach{ride->savedRides.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,12,0,12);addView(TextView(context).apply{text="${if(ride.recovered)"Recovered" else "Completed"}\n${dates.format(Date(ride.startTime))}\nTotal ${duration(ride.totalDurationMs())}\nPaused ${duration(ride.pausedDurationMs())}\nRecorded ${duration(ride.activeDurationMs())}\n${ride.sampleCount} samples";setTextColor(getColor(R.color.text_primary));textSize=16f});addView(LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;addView(Button(context).apply{text="Export";setOnClickListener{exportSession(ride)}});addView(Button(context).apply{text="Delete";setOnClickListener{if(r.deleteSession(ride.id))renderSavedRides()}})})})}}
    private fun exportSession(s:RideSession){AlertDialog.Builder(this).setTitle("Export ride").setItems(arrayOf("CSV","GPX","Both")){_,which->when(which){0->beginExport(s,CREATE_CSV,"text/csv","csv");1->if(hasGpx(s))beginExport(s,CREATE_GPX,"application/gpx+xml","gpx");else showError(NoGpxDataException());else->if(hasGpx(s)){pendingExportSessionId=s.id;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),CREATE_BOTH)}else showError(NoGpxDataException())}}.show()}
    private fun hasGpx(s:RideSession):Boolean{val csv=service?.sessionFile(s.id)?:return false;return gpxExporter.loadPoints(csv,service?.sessionGpxDataFile(s.id)).isNotEmpty()}
    private fun beginExport(s:RideSession,request:Int,mime:String,extension:String){pendingExportSessionId=s.id;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type=mime;putExtra(Intent.EXTRA_TITLE,"${exportBaseName(s)}.$extension")},request)}
    private fun exportBaseName(s:RideSession)="ducati-ride-"+SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date(s.startTime))
    private fun selectFirmware()=startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/octet-stream";addCategory(Intent.CATEGORY_OPENABLE)},OPEN_FIRMWARE)
    private fun startFirmwareUpdate(uri:Uri)=try{val f=contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Cannot read firmware file");require(f.isNotEmpty()){ "Firmware file is empty"};service?.startOta(f)}catch(e:Exception){showError(e)}
    @Deprecated("Activity Result API can be introduced later.") override fun onActivityResult(code:Int,result:Int,data:Intent?){super.onActivityResult(code,result,data);if(result!=RESULT_OK)return;val uri=data?.data?:return;when(code){CREATE_CSV->exportCsv(uri);CREATE_GPX->exportGpx(uri);CREATE_BOTH->exportBoth(uri);OPEN_FIRMWARE->startFirmwareUpdate(uri)}}
    private fun pendingRide():Pair<RideSession,java.io.File>{val runtime=service?:error("Ride service unavailable");val id=pendingExportSessionId?:error("No ride selected");val ride=runtime.listSessions().firstOrNull{it.id==id}?:error("Saved ride is unavailable");return ride to (runtime.sessionFile(id)?:error("Saved ride is unavailable"))}
    private fun exportCsv(uri:Uri){try{val(_,csv)=pendingRide();csvExporter.export(csv,contentResolver.openOutputStream(uri)?:error("Cannot create export file"));Toast.makeText(this,"CSV exported",Toast.LENGTH_SHORT).show()}catch(e:Exception){showError(e)}finally{pendingExportSessionId=null}}
    private fun exportGpx(uri:Uri){try{val(ride,csv)=pendingRide();val out=contentResolver.openOutputStream(uri)?:error("Cannot create export file");gpxExporter.export(csv,service?.sessionGpxDataFile(ride.id),ride,out);Toast.makeText(this,"GPX exported",Toast.LENGTH_SHORT).show()}catch(e:Exception){showError(e)}finally{pendingExportSessionId=null}}
    private fun exportBoth(tree:Uri){try{val(ride,csv)=pendingRide();if(gpxExporter.loadPoints(csv,service?.sessionGpxDataFile(ride.id)).isEmpty())throw NoGpxDataException();val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));val base=exportBaseName(ride);val csvUri=DocumentsContract.createDocument(contentResolver,parent,"text/csv","$base.csv")?:error("Cannot create CSV");val gpxUri=DocumentsContract.createDocument(contentResolver,parent,"application/gpx+xml","$base.gpx")?:error("Cannot create GPX");csvExporter.export(csv,contentResolver.openOutputStream(csvUri)?:error("Cannot write CSV"));gpxExporter.export(csv,service?.sessionGpxDataFile(ride.id),ride,contentResolver.openOutputStream(gpxUri)?:error("Cannot write GPX"));Toast.makeText(this,"CSV and GPX exported",Toast.LENGTH_SHORT).show()}catch(e:Exception){showError(e)}finally{pendingExportSessionId=null}}
    private fun requestCorePermissionsIfNeeded(){val m=mutableListOf<String>();if(!hasBlePermissions()){m+=Manifest.permission.BLUETOOTH_SCAN;m+=Manifest.permission.BLUETOOTH_CONNECT};if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)m+=Manifest.permission.POST_NOTIFICATIONS;if(m.isNotEmpty())requestPermissions(m.toTypedArray(),REQUEST_CORE)}
    private fun requestLocationPermission()=requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),REQUEST_LOCATION)
    private fun hasBlePermissions()=checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    private fun hasLocationPermission()=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(code:Int,p:Array<out String>,r:IntArray){super.onRequestPermissionsResult(code,p,r);service?.permissionsChanged();if(code==REQUEST_CORE&&android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)Toast.makeText(this,"Ride recording still works, but Android may hide its notification",Toast.LENGTH_LONG).show()}
    private fun label(v:GnssStatus)=v.name.lowercase().replace('_',' ').replaceFirstChar(Char::uppercase)
    private fun accuracyLabel(v:Int?)=when(v){android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH->"High";android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM->"Medium";android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW->"Low";android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE->"Unreliable";else->"Available"}
    private fun bikeLabel(v:BleConnectionState)=when(v){BleConnectionState.CONNECTED->"Bike connected";BleConnectionState.RECONNECTING->"Bike disconnected · Waiting for Ducati…";BleConnectionState.CONNECTING->"Connecting to bike…";BleConnectionState.DISCONNECTED->"Bike disconnected"}
    private fun duration(ms:Long):String{val s=ms.coerceAtLeast(0)/1000;return if(s>=3600)"%dh %02dm %02ds".format(s/3600,(s%3600)/60,s%60)else "%dm %02ds".format(s/60,s%60)}
    private fun showError(e:Exception)=Toast.makeText(this,e.message?:"Operation failed",Toast.LENGTH_LONG).show()
}
