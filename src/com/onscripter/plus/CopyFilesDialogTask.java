package com.onscripter.plus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.StatFs;
import android.text.Html;
import android.text.Spanned;
import android.text.format.Formatter;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class CopyFilesDialogTask {

    public static class CopyFileInfo {
        public CopyFileInfo(String src, String dst) {
            source = src;
            destination = dst;
        }
        public String source;
        public String destination;
    }

    public static interface CopyFilesDialogListener {
        public void onCopyCompleted(Result result);
    }

    public static enum Result { SUCCESS, CANCELLED, COPY_ERROR, NO_SPACE_ERROR, NO_FILE_SELECTED };

    private final Context mCtx;
    private final CopyFilesDialogListener mListener;
    private CopyFileInfo[] mInfo;
    private boolean mIsRunning;
    private final int mExtSDCardPathLength;

    public CopyFilesDialogTask(Context ctx, CopyFilesDialogListener listener) {
        mCtx = ctx;
        mListener = listener;
        mIsRunning = false;
        mExtSDCardPathLength = Environment2.getExternalSDCardDirectory().getPath().length();
    }

    public void executeCopy(CopyFileInfo[] info) {
        if (info.length > 0) {
            synchronized (this) {
                if (mIsRunning) {
                    return;
                } else {
                    mIsRunning = true;
                    mInfo = info;
                }
            }
            new InternalFileSpaceDialogTask().execute();
        } else if (mListener != null) {
            mListener.onCopyCompleted(Result.SUCCESS);
        }
    }

    private void scanFinishedUnsuccessfully(Result result) {
        synchronized (this) {
            mIsRunning = false;
        }
        if (mListener != null) {
            mListener.onCopyCompleted(result);
        }
    }

    private void scanFinished(long totalBytes) {
        if (totalBytes > 0L) {
            new InternalCopyDialogTask(totalBytes).execute();
        } else {
            scanFinishedUnsuccessfully(Result.NO_FILE_SELECTED);
        }
    }

    private void copyFinished(Result result) {
        synchronized (this) {
            mInfo = null;
            mIsRunning = false;
        }
        if (mListener != null) {
            mListener.onCopyCompleted(result);
        }
    }

    private class InternalFileSpaceDialogTask extends ProgressDialogAsyncTask<Void, Void, Long> {

        private final ArrayList<Pair<Spanned, Long>> mListing;
        private long mRemainingInternalBytes;
        private ListView mFileListView;
        private TextView mRemainingText;
        private long mCurrentSumBytes;

        public InternalFileSpaceDialogTask() {
            super(mCtx, mCtx.getString(R.string.dialog_scan_files_title), true);
            mListing = new ArrayList<Pair<Spanned, Long>>(mInfo.length);
            mCurrentSumBytes = 0;
        }

        /**
         * Recursively scans each file in given folder (or file) and returns the
         * total sum of their file size. Maybe slow with many files
         * @param source
         * @return
         */
        private long scanTotalBytes(File source) {
            long totalBytes = 0;
            if (source.isFile()) {
                totalBytes += source.length();
            } else {
                File[] children = source.listFiles();
                if (children != null) {
                    for (int i = 0; i < children.length; i++) {
                        totalBytes += scanTotalBytes(children[i]);
                    }
                }
            }
            return totalBytes;
        }

        private void updateAndRecalculateList() {
            mRemainingText.setText(Formatter.formatFileSize(mCtx, mRemainingInternalBytes - mCurrentSumBytes));

            for (int i = 0; i < mFileListView.getChildCount(); i++) {
                CheckedTextView tv = (CheckedTextView)mFileListView.getChildAt(i);
                if (!tv.isChecked()) {
                    if (mRemainingInternalBytes - mCurrentSumBytes - mListing.get(i).second < 0) {
                        tv.setEnabled(false);
                    } else {
                        tv.setEnabled(true);
                    }
                }
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        protected Long doInBackground(Void... params) {
            long bytes = 0;

            // Scan files
            for (int i = 0; i < mInfo.length; i++) {
                if (isCancelled()) {
                    return 0L;
                }
                long b = scanTotalBytes(new File(mInfo[i].source));
                bytes += b;
                Spanned listing = Html.fromHtml("<b>" + mInfo[i].source.substring(mExtSDCardPathLength) + "</b><br><small>" + Formatter.formatFileSize(mCtx, b) + "</small>");
                mListing.add(new Pair<Spanned, Long>(listing, b));
            }

            // Read remaining memory on internal storage
            File path = Environment2.getInternalStorageDirectory();
            StatFs stat = new StatFs(path.getPath());
            mRemainingInternalBytes = stat.getBlockSize() * stat.getAvailableBlocks();
            return bytes;
        }


        @Override
        protected void onPostExecute(Long totalBytes) {
            super.onPostExecute(totalBytes);

            // If not enough room to copy, we will show a dialog to allow user to choose what to copy
            if (mRemainingInternalBytes < totalBytes) {
                if (mInfo.length == 1) {
                    // Only copying one file and there is no more space, can't copy then
                    scanFinishedUnsuccessfully(Result.NO_SPACE_ERROR);
                } else {
                    // Inflate the dialog
                    LayoutInflater inflater = (LayoutInflater) mCtx.getSystemService( Context.LAYOUT_INFLATER_SERVICE);
                    LinearLayout view = (LinearLayout) inflater.inflate(R.layout.selective_file_dialog, null);

                    // Get the elements of the list, setup the listview and events
                    mRemainingText = (TextView)view.findViewById(R.id.spaceRemaining);
                    mFileListView = (ListView)view.findViewById(R.id.fileList);
                    mFileListView.setAdapter(new ViewAdapterBase<Pair<Spanned, Long>>(mCtx,
                            R.layout.selective_file_item, new int[]{android.R.id.text1}, mListing) {
                        @Override
                        protected void setWidgetValues(int position, Pair<Spanned, Long> item,
                                View[] elements, View layout) {
                            ((CheckedTextView)elements[0]).setText(item.first);
                        }
                    });
                    mFileListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                        @Override
                        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                            // change the checkbox state
                            CheckedTextView checkedTextView = ((CheckedTextView)view);
                            if (checkedTextView.isEnabled()) {
                                checkedTextView.toggle();

                                // Update the UI and count
                                if (checkedTextView.isChecked()) {
                                    mCurrentSumBytes += mListing.get(position).second;
                                } else {
                                    mCurrentSumBytes -= mListing.get(position).second;
                                }
                                updateAndRecalculateList();
                            }
                        }
                    });

                    // Build the dialog and attach the layout
                    new AlertDialog.Builder(mCtx)
                        .setTitle(R.string.dialog_select_files_copy_title)
                        .setView(view)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                int j = 0;
                                ArrayList<CopyFileInfo> info = new ArrayList<CopyFileInfo>(mFileListView.getChildCount());
                                for (int i = 0; i < mFileListView.getChildCount(); i++) {
                                    if (((CheckedTextView)mFileListView.getChildAt(i)).isChecked()) {
                                        info.add(mInfo[i]);
                                    }
                                }
                                mInfo = info.toArray(new CopyFileInfo[info.size()]);
                                scanFinished(mCurrentSumBytes);
                            }
                        })
                        .setNeutralButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // Cancel out
                                scanFinishedUnsuccessfully(Result.CANCELLED);
                            }
                        })
                        .show();
                    updateAndRecalculateList();
                }
            } else {
                scanFinished(totalBytes);
            }
        }
    }

    /**
     * This is the task that creates the copy dialog and copies the files from
     * source to destination
     * @author CHaSEdBYmAnYcrAZy
     *
     */
    private class InternalCopyDialogTask extends AsyncTask<Void, Void, Result> {

        private Dialog mDialog;
        private final LinearLayout mLayout;
        private final TextView mCurrentFileNameText;
        private final TextView mCurrentFileSizeText;
        private final TextView mCurrentPercentText;
        private final TextView mOverallPercentText;
        private final ProgressBar mCurrentPercentProgress;
        private final ProgressBar mOverallPercentProgress;

        // Progress data
        private final long mTotalBytesCopying;
        private String mCurrentFile;
        private long mCurrentFileSize;
        private long mCurrentFileTotalSize;
        private int mCurrentFilePercentage;

        public InternalCopyDialogTask(long totalBytes) {
            mTotalBytesCopying = totalBytes;

            // Inflate the dialog
            LayoutInflater inflater = (LayoutInflater) mCtx.getSystemService( Context.LAYOUT_INFLATER_SERVICE);
            mLayout = (LinearLayout) inflater.inflate(R.layout.copy_file_dialog, null);

            mCurrentFileNameText = (TextView) mLayout.findViewById(R.id.filename);
            mCurrentFileSizeText = (TextView) mLayout.findViewById(R.id.filesize);
            mCurrentPercentText = (TextView) mLayout.findViewById(R.id.filePercent);
            mOverallPercentText = (TextView) mLayout.findViewById(R.id.overallPercent);
            mCurrentPercentProgress = (ProgressBar) mLayout.findViewById(R.id.fileProgressbar);
            mOverallPercentProgress = (ProgressBar) mLayout.findViewById(R.id.overallProgressbar);
        }

        private void show() {
            if (mDialog == null) {
                mDialog = new AlertDialog.Builder(mCtx)
                    .setCancelable(false)
                    .setTitle(R.string.dialog_copy_files_title)
                    .setView(mLayout)
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            cancel(true);
                        }
                    })
                    .create();
            }
            mDialog.show();
        }

        /**
         * Copies a file or folder recursively. Please make sure the source and destination
         * are both either folders or files.
         * @param source
         * @param destination
         * @return true if success
         */
        private boolean copy(File source, File destination) {
            return source.isFile() ? copyFile(source, destination) : copyFolder(source, destination);
        }

        private boolean copyFolder(File source, File destination) {
            File[] children = source.listFiles();
            if (!destination.exists() && !destination.mkdir()) {
                // Failed to make a new directory
                Log.e("CopyFilesDialogTask", "Failed to make directory '" + destination.getAbsolutePath() + "'");
                return false;
            }
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    if (isCancelled()) {
                        return false;
                    }
                    File dstFile = new File(destination + "/" + children[i].getName());

                    // Failed to copy, kill the copying routine
                    if (!copy(children[i], dstFile)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean copyFile(File source, File destination) {
            InputStream in = null;
            OutputStream out = null;
            int copiedLen = 0;
            mCurrentFileTotalSize = source.length();
            mCurrentFile = source.getPath().substring(mExtSDCardPathLength);
            mCurrentFilePercentage = 0;
            try {
                in = new FileInputStream(source);
                out = new FileOutputStream(destination);

                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    if (isCancelled()) {
                        break;
                    }

                    out.write(buf, 0, len);
                    copiedLen += len;
                    mCurrentFileSize += len;

                    int percent = (int) Math.round(copiedLen * 100.0 / mCurrentFileTotalSize);
                    if (percent != mCurrentFilePercentage) {
                        mCurrentFilePercentage = percent;
                        publishProgress();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                    if (out != null) {
                        out.close();
                    }
                } catch(IOException e) {}
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(Void... values) {
            super.onProgressUpdate(values);
            int totalProgress = (int)Math.round(mCurrentFileSize * 100.0 / mTotalBytesCopying);
            mCurrentFileNameText.setText(mCurrentFile);
            mCurrentFileSizeText.setText(Formatter.formatFileSize(mCtx, mCurrentFileTotalSize));
            mCurrentPercentText.setText(mCurrentFilePercentage + "%");
            mOverallPercentText.setText(totalProgress + "%");
            mCurrentPercentProgress.setProgress(mCurrentFilePercentage);
            mOverallPercentProgress.setProgress(totalProgress);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            // If only copying 1 block, then don't need overall
            if (mInfo.length <= 1) {
                mOverallPercentText.setVisibility(View.GONE);
                mOverallPercentProgress.setVisibility(View.GONE);
            } else {
                mOverallPercentText.setVisibility(View.VISIBLE);
                mOverallPercentProgress.setVisibility(View.VISIBLE);
            }
            show();
        }

        @Override
        protected Result doInBackground(Void... params) {
            // Copy files
            mCurrentFileSize = 0;
            for (int i = 0; i < mInfo.length; i++) {
                if (isCancelled()) {
                    return Result.CANCELLED;
                }
                if (!copy(new File(mInfo[i].source), new File(mInfo[i].destination))) {
                    return isCancelled() ? Result.CANCELLED : Result.COPY_ERROR;
                }
            }
            return Result.SUCCESS;
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            copyFinished(Result.CANCELLED);
        }

        @Override
        protected void onPostExecute(Result result) {
            super.onPostExecute(result);
            copyFinished(result);
            mDialog.dismiss();
        }
    }
}