package com.doxua.www.face2rec;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_face;
import org.bytedeco.javacpp.opencv_objdetect;
import org.bytedeco.javacv.AndroidFrameConverter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.bytedeco.javacpp.opencv_imgproc.CV_BGR2GRAY;
import static org.bytedeco.javacpp.opencv_imgproc.cvtColor;
import static org.bytedeco.javacpp.opencv_imgproc.rectangle;
import static org.bytedeco.javacpp.opencv_imgproc.resize;
import static org.opencv.core.Core.LINE_8;

public class MainActivity extends AppCompatActivity {

    //For Photos
    public static int NOTIFICATION_ID = 0;
    Bitmap bitmapSelectGallery = null;
    Bitmap bitmapAutoGallery;
    static Bitmap finalBitmapPic;
    GalleryObserver glassDirectoryFileObserver;
    Bitmap bitmapNoFace;

    private static MainActivity instance;

    //For Photos ^


    public static final String TAG = "MainActivity";
    private static final int ACCEPT_LEVEL = 3500;
    private static final int MIDDLE_ACCEPT_LEVEL = 4000;
    private static final int PICK_IMAGE = 100;
    private static final int IMG_SIZE = 160;

    private boolean mPermissionReady;

    // Views.
    private ImageView imageView;
    private TextView tv;
    private TextView result_information;

    // Face Detection.
    private opencv_objdetect.CascadeClassifier faceDetector;
    private int absoluteFaceSize = 0;

    // Face Recognition.
    private opencv_face.FaceRecognizer faceRecognizer = opencv_face.EigenFaceRecognizer.create();

    String personName;
    int acceptanceLevel;
    int prediction;
    int personId;
    String matchText;
    String info;
    String nothing = " ";
    String moreInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create the image view and text view.
        imageView = (ImageView) findViewById(R.id.imageView);
        tv = (TextView) findViewById(R.id.predict_faces);
        tv.setMovementMethod(new ScrollingMovementMethod());

        result_information = (TextView) findViewById(R.id.result);
        result_information.setMovementMethod(new ScrollingMovementMethod());


        int cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int storagePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        mPermissionReady = cameraPermission == PackageManager.PERMISSION_GRANTED && storagePermission == PackageManager.PERMISSION_GRANTED;

        if (!mPermissionReady) {
            requirePermissions();
        }

        // Pick an image and recognize.
        Button pickImageButton = (Button) findViewById(R.id.btnGallery);
        pickImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGallery();
            }
        });

        instance = this;

        glassDirectoryFileObserver = new GalleryObserver("/storage/emulated/0/MyGlass/");
        glassDirectoryFileObserver.startWatching();


        lastPhotoInGallery();

    }


    private void requirePermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, 11);
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Map<String, Integer> perm = new HashMap<>();
        perm.put(Manifest.permission.CAMERA, PackageManager.PERMISSION_DENIED);
        perm.put(Manifest.permission.WRITE_EXTERNAL_STORAGE, PackageManager.PERMISSION_DENIED);
        for (int i = 0; i < permissions.length; i++) {
            perm.put(permissions[i], grantResults[i]);
        }
        if (perm.get(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && perm.get(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            mPermissionReady = true;
        } else {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
                    || !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.permission_warning)
                        .setPositiveButton(R.string.dismiss, null)
                        .show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }


    private void openGallery() {
        Intent gallery =
                new Intent(Intent.ACTION_PICK,
                        MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        startActivityForResult(gallery, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == PICK_IMAGE) {
            Uri imageUri = data.getData();

            // Convert to Bitmap.
            try {
                bitmapSelectGallery = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            } catch (IOException e) {
                e.printStackTrace();
            }
            imageView.setImageBitmap(bitmapSelectGallery);

            if (bitmapSelectGallery != null) {
                detectDisplayAndRecognize(bitmapSelectGallery);
            }
        }
    }

    public static MainActivity getInstance() {
        return instance;
    }


    public void lastPhotoInGallery() {
        // Find the last picture

        String[] projection = new String[]{
                MediaStore.Images.ImageColumns._ID,
                MediaStore.Images.ImageColumns.DATA,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Images.ImageColumns.MIME_TYPE
        };
        final Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null,
                null, MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC");

        // Put it in the image view


        if (cursor.moveToFirst()) {
            final ImageView imageView = (ImageView) findViewById(R.id.imageView);
            String imageLocation = cursor.getString(1);
            File imageFile = new File(imageLocation);

            if (imageFile.exists()) {
                bitmapAutoGallery = BitmapFactory.decodeFile(imageLocation);

                if (bitmapAutoGallery != null) {
                    runOnUiThread(new Runnable() {

                        @Override
                        public void run() {

                            imageView.setImageBitmap(bitmapAutoGallery);

                            detectDisplayAndRecognize(bitmapAutoGallery);
                        }
                    });


                }
            }
        }

    }

    public void notifications() {
        //This code is required to send notifications to the phone and Google Glass
        //Google Glass automatically will display phone notifications as part of its design

        //Need to increase notification id by 1 in order to have multiple notifications displayed, otherwise notifications
        //will overwrite previous notification
        NOTIFICATION_ID++;


        //This is used to open the new screen when the notification is clicked on the phone:

        Intent detailsIntent = new Intent(MainActivity.this, MainActivity.class);
        PendingIntent detailsPendingIntent = PendingIntent.getActivity(MainActivity.this, NOTIFICATION_ID, detailsIntent, PendingIntent.FLAG_UPDATE_CURRENT);


        //To determine what needs to be displayed
        if (bitmapSelectGallery != null) {

            //bitmapSelectGallery is for images selected from Gallery on phone
            //Need to resize bitmaps otherwise app will crash and/or not display photo correctly
            finalBitmapPic = Bitmap.createScaledBitmap(bitmapSelectGallery, 500, 800, false);

        } else if (bitmapAutoGallery != null) {
            //bitmapAutoGallery is for the image that auto loads on app since it is latest image in Gallery
            //Need to resize bitmaps otherwise app will crash and/or not display photo correctly
            finalBitmapPic = Bitmap.createScaledBitmap(bitmapAutoGallery, 500, 800, false);
        } else {

            finalBitmapPic = Bitmap.createScaledBitmap(bitmapNoFace, 500, 800, false);


        }


        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(MainActivity.this)


                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setLargeIcon(finalBitmapPic)
                .setAutoCancel(true)
                .setContentIntent(detailsPendingIntent)
                .setContentText(matchText + "\n \n" + moreInfo)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(matchText + "\n" + moreInfo))
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_compass, "Press to Clear and return to Main", detailsPendingIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, mBuilder.build());

        bitmapSelectGallery = null;

    }


    /**
     * Face Detection.
     * Face Recognition.
     * Display the detection result and recognition result.
     *
     * @param bitmap
     */
    void detectDisplayAndRecognize(Bitmap bitmap) {
        // Create a new gray Mat.
        opencv_core.Mat greyMat = new opencv_core.Mat();
        // JavaCV frame converters.
        AndroidFrameConverter converterToBitmap = new AndroidFrameConverter();
        OpenCVFrameConverter.ToMat converterToMat = new OpenCVFrameConverter.ToMat();

        // -----------------------------------------------------------------------------------------
        //                              Convert to mat for processing
        // -----------------------------------------------------------------------------------------
        // Convert to Bitmap.
        Frame frame = converterToBitmap.convert(bitmap);
        // Convert to Mat.
        opencv_core.Mat colorMat = converterToMat.convert(frame);

        // Convert to Gray scale.
        cvtColor(colorMat, greyMat, CV_BGR2GRAY);
        // Vector of rectangles where each rectangle contains the detected object.
        opencv_core.RectVector faces = new opencv_core.RectVector();

        // -----------------------------------------------------------------------------------------
        //                                  FACE DETECTION
        // -----------------------------------------------------------------------------------------
        // Load the CascadeClassifier class to detect objects.
        faceDetector = loadClassifierCascade(this, R.raw.frontalface);
        // Detect the face.
        faceDetector.detectMultiScale(greyMat, faces, 1.25f, 3, 1,
                new opencv_core.Size(absoluteFaceSize, absoluteFaceSize),
                new opencv_core.Size(4 * absoluteFaceSize, 4 * absoluteFaceSize));

        // Count number of faces.
        int numFaces = (int) faces.size();

        // -----------------------------------------------------------------------------------------
        //                                       DISPLAY
        // -----------------------------------------------------------------------------------------
        if (numFaces > 0) {
            // Multiple face detection.
            for (int i = 0; i < numFaces; i++) {

                int x = faces.get(i).x();
                int y = faces.get(i).y();
                int w = faces.get(i).width();
                int h = faces.get(i).height();

                rectangle(colorMat, new opencv_core.Point(x, y), new opencv_core.Point(x + w, y + h), opencv_core.Scalar.GREEN, 2, LINE_8, 0);

                // ---------------------------------------------------------------------------------
                //                      Convert back to bitmap for displaying
                // ---------------------------------------------------------------------------------
                // Convert processed Mat back to a Frame
                frame = converterToMat.convert(colorMat);
                // Copy the data to a Bitmap for display or something
                Bitmap bm = converterToBitmap.convert(frame);

                // Display the picked image.
                imageView.setImageBitmap(bm);
            }
        } else {
            imageView.setImageBitmap(bitmap);
        }

        // -----------------------------------------------------------------------------------------
        //                                  FACE RECOGNITION
        // -----------------------------------------------------------------------------------------
        if (numFaces == 0) {

            tv.setText("No Faces Detected");
            bitmapNoFace = bitmapAutoGallery;

            matchText = tv.getText().toString();

            result_information.setText(nothing);
            moreInfo = result_information.getText().toString();

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    notifications();
                }
            }, 2000);

        } else {

            recognizeMultiple(this, faces.get(0), greyMat, tv);

        }
    }

    /***********************************************************************************************
     *
     *
     *          USING THE MODEL IS STORED AND INSTALLED TOGETHER WHEN THE APP IS INSTALLED
     *
     *
     **********************************************************************************************/
    /**
     * Predict using one model only but can predict faces of different people.
     * Recognize multiple faces using only one model.
     * prediction = 0 Angelina Jolie
     * prediction = 1 Tom Cruise
     * IMPORTANT!
     *
     * @param dadosFace
     * @param greyMat
     */
    void recognizeMultiple(Context context, opencv_core.Rect dadosFace, opencv_core.Mat greyMat, TextView tv) {
        personId = 0;
        personName = "";

        // Load the correct model for our face recognition.
        File f = loadTrainedModel(this, R.raw.eigenfacesclassifier);

        // Loads a persisted model and state from a given XML or YAML file.
        faceRecognizer.read(f.getAbsolutePath());

        opencv_core.Mat detectedFace = new opencv_core.Mat(greyMat, dadosFace);
        resize(detectedFace, detectedFace, new opencv_core.Size(IMG_SIZE, IMG_SIZE));

        IntPointer label = new IntPointer(1);
        DoublePointer reliability = new DoublePointer(1);
        faceRecognizer.predict(detectedFace, label, reliability);

        // Display on the text view what we found.
        prediction = label.get(0);
        acceptanceLevel = (int) reliability.get(0);

        if (prediction == 0) {
            personName = "Gabriella Mayorga";
            personId = 1;
        }

        if (prediction == 1) {
            personName = "Xuan Do";
            personId = 2;
        }
        displayMatchInfo();
    }
        public void displayMatchInfo () {

            // -----------------------------------------------------------------------------------------
            //                         DISPLAY THE FACE RECOGNITION PREDICTION
            // -----------------------------------------------------------------------------------------
            if ((prediction != 0 && prediction != 1) || acceptanceLevel > MIDDLE_ACCEPT_LEVEL) {
                // Display on text view, not matching or unknown person.
                tv.setText("Unknown" +
                        "\nAcceptance Level: " + acceptanceLevel
                );
                matchText = tv.getText().toString();

                result_information.setText(nothing);
                moreInfo = result_information.getText().toString();

                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        notifications();
                    }
                }, 2000);

            } else if (acceptanceLevel >= ACCEPT_LEVEL && acceptanceLevel <= MIDDLE_ACCEPT_LEVEL) {
                tv.setText(
                        "Potential Match: " + personName +
                                "\nWarning! Acceptance Level high!" +
                                "\nAcceptance Level: " + acceptanceLevel

                );
                matchText = tv.getText().toString();

                result_information.setText(nothing);
                moreInfo = result_information.getText().toString();

                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        notifications();
                    }
                }, 2000);

            } else {
                // Display the information for the matching image.
                tv.setText(
                        "Match found: " + personName +
                                "\nAcceptance Level: " + acceptanceLevel

                );
                matchText = tv.getText().toString();

                if (personId >= 1) {
                    DatabaseAccess databaseAccess = DatabaseAccess.getInstance(getApplicationContext());
                    databaseAccess.open();

                    info = databaseAccess.getInformation(personId);
                    result_information.setText(info);
                    moreInfo = result_information.getText().toString();

                    databaseAccess.close();

                }
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        notifications();
                    }
                }, 2000);
            } // End of prediction.



        }


        /***********************************************************************************************
         *
         *
         *                                      HELPER METHODS
         *
         *
         **********************************************************************************************/
        /**
         * Load the trained model for Face Recognition.
         * @param context
         * @param resId
         * @return
         */
        public static File loadTrainedModel (Context context,int resId){
            FileOutputStream fos = null;
            InputStream inputStream;

            inputStream = context.getResources().openRawResource(resId);
            File xmlDir = context.getDir("yml", Context.MODE_PRIVATE);
            File modelFile = new File(xmlDir, "temp.yml");
            try {
                fos = new FileOutputStream(modelFile);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Log.d(TAG, "Can\'t load the cascade file");
                e.printStackTrace();
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            File f = new File(modelFile.getAbsolutePath());
            return f;
        }

        /**
         * Load the CascadeClassifier for Face Detection.
         * @param context
         * @param resId
         * @return
         */
        public static opencv_objdetect.CascadeClassifier loadClassifierCascade (Context context,
        int resId){
            FileOutputStream fos = null;
            InputStream inputStream;

            inputStream = context.getResources().openRawResource(resId);
            File xmlDir = context.getDir("xml", Context.MODE_PRIVATE);
            File cascadeFile = new File(xmlDir, "temp.xml");
            try {
                fos = new FileOutputStream(cascadeFile);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Log.d(TAG, "Can\'t load the cascade file");
                e.printStackTrace();
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            opencv_objdetect.CascadeClassifier detector = new opencv_objdetect.CascadeClassifier(cascadeFile.getAbsolutePath());
            if (detector.isNull()) {
                Log.e(TAG, "Failed to load cascade classifier");
                detector = null;
            } else {
                Log.i(TAG, "Loaded cascade classifier from " + cascadeFile.getAbsolutePath());
            }
            // Delete the temporary directory.
            cascadeFile.delete();
            return detector;
        }
    }
