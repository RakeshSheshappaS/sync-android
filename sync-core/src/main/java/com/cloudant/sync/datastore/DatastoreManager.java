/**
 * Original iOS version by  Jens Alfke, ported to Android by Marty Schoch
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 *
 * Modifications for this distribution by Cloudant, Inc., Copyright (c) 2013 Cloudant, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.datastore;

import com.cloudant.common.Log;
import com.cloudant.sync.notifications.DatabaseClosed;
import com.cloudant.sync.notifications.DatabaseCreated;
import com.cloudant.sync.notifications.DatabaseOpened;
import com.cloudant.sync.notifications.DatabaseDeleted;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>Manages a set of {@link Datastore} objects, with their underlying disk
 * storage residing in a given directory.</p>
 *
 * <p>In general, a directory used for storing datastores -- that is, managed
 * by this manager -- shouldn't be used for storing other data. The manager
 * object assumes all data within is managed by itself, so adding other files
 * to the directory may cause them to be deleted.</p>
 *
 * <p>A datastore's on-disk representation is a single file containing
 * all its data. In future, there may be other files and folders per
 * datastore.</p>
 */
public class DatastoreManager {

    private final static String LOG_TAG = "DatastoreManager";

    private final String path;

    private final Map<String, Datastore> openedDatastores = Collections.synchronizedMap(new HashMap<String, Datastore>());

    /**
     * The regex used to validate a datastore name, {@value}.
     */
    protected static final String LEGAL_CHARACTERS = "^[a-zA-Z]+[a-zA-Z0-9_]*";

    private final EventBus eventBus = new EventBus();

    /**
     * <p>Constructs a {@code DatastoreManager} to manage a directory.</p>
     *
     * <p>Datastores are created within the {@code directoryPath} directory.
     * In general, this folder should be under the control of, and only used
     * by, a single {@code DatastoreManager} object at any time.</p>
     *
     * @param directoryPath root directory to manage
     *
     * @see DatastoreManager#DatastoreManager(java.io.File)
     */
    public DatastoreManager(String directoryPath) {
        this(new File(directoryPath));
    }

    /**
     * <p>Constructs a {@code DatastoreManager} to manage a directory.</p>
     *
     * <p>Datastores are created within the {@code directoryPath} directory.
     * In general, this folder should be under the control of, and only used
     * by, a single {@code DatastoreManager} object at any time.</p>
     *
     * @param directoryPath root directory to manage
     *
     * @throws IllegalArgumentException if the {@code directoryPath} is not a
     *          directory or isn't writable.
     */
    public DatastoreManager(File directoryPath) {
        Log.d(LOG_TAG, "Datastore path: " + directoryPath);
        if(!directoryPath.isDirectory() ) {
            throw new IllegalArgumentException("Input path is not a valid directory");
        } else if(!directoryPath.canWrite()) {
            throw new IllegalArgumentException("Datastore directory is not writable");
        }
        this.path = directoryPath.getAbsolutePath();
    }

    /**
     * <p>Returns the path to the directory this object manages.</p>
     * @return the absolute path to the directory this object manages.
     */
    public String getPath() {
        return path;
    }

    /**
     * <p>Opens a datastore.</p>
     *
     * <p>This method finds the appropriate datastore file for a
     * datastore, then initialises a {@link Datastore} object connected
     * to that underlying storage file.</p>
     *
     * <p>If the datastore was successfully created and opened, a 
     * {@link com.cloudant.sync.notifications.DatabaseOpened DatabaseOpened}
     * event is posted on the event bus.</p>
     *
     * @param dbName name of datastore to open
     * @return {@code Datastore} with the given name
     *
     * @see DatastoreManager#getEventBus() 
     */
    public Datastore openDatastore(String dbName) {
        Preconditions.checkArgument(dbName.matches(LEGAL_CHARACTERS),
                "Database name can only contain letter, underscore and digit, " +
                        "and start with letter: " + dbName);

        if (!openedDatastores.containsKey(dbName)) {
            synchronized (openedDatastores) {
                if (!openedDatastores.containsKey(dbName)) {
                    Datastore ds = createDatastore(dbName);
                    ds.getEventBus().register(this);
                    openedDatastores.put(dbName, ds);
                }
            }
        }
        return openedDatastores.get(dbName);
    }

    /**
     * <p>Deletes a datastore's files from disk.</p>
     *
     * <p>This operation deletes a datastore's files from disk. It is therefore
     * a not undo-able. To confirm, this only deletes local data; data
     * replicated to remote databases is not affected.</p>
     *
     * <p>Any {@link Datastore} objects referring to the deleted files will be
     * in an unknown state. Therefore, they should be disposed of prior to
     * deleting the data. Currently, no checks for open datastores are carried
     * out before attempting the delete.</p>
     *
     * <p>If the datastore was successfully deleted, a 
     * {@link com.cloudant.sync.notifications.DatabaseDeleted DatabaseDeleted} 
     * event is posted on the event bus.</p>
     *
     * @throws IOException if the datastore doesn't exist on disk or there is
     *      a problem deleting the files.
     *
     * @see DatastoreManager#getEventBus() 
     */
    public void deleteDatastore(String dbName) throws IOException {
        Preconditions.checkNotNull(dbName, "Datastore name must not be null");

        synchronized (openedDatastores) {
            String dbDirectory = getDatastoreDirectory(dbName);
            File dir = new File(dbDirectory);
            try {
                if (!dir.exists()) {
                    String msg = String.format(
                            "Datastore %s doesn't exist on disk", dbName
                            );
                    throw new IOException(msg);
                } else {
                    FileUtils.deleteDirectory(dir);
                    eventBus.post(new DatabaseDeleted(dbName));
                }
            } finally {
                if(openedDatastores.containsKey(dbName)) {
                    openedDatastores.remove(dbName);
                }
            }
        }
    }

    private Datastore createDatastore(String dbName) {
        try {
            String dbDirectory = this.getDatastoreDirectory(dbName);
            boolean dbDirectoryExist = new File(dbDirectory).exists();
            Log.i(LOG_TAG, "path: " + this.path);
            Log.i(LOG_TAG, "dbDirectory: " + dbDirectory);
            Log.i(LOG_TAG, "dbDirectoryExist: " + dbDirectoryExist);
            // dbDirectory will created in BasicDatastore constructor
            // if it does not exist
            BasicDatastore ds = new BasicDatastore(dbDirectory, dbName);
            if(!dbDirectoryExist) {
                this.eventBus.post(new DatabaseCreated(dbName));
            }
            eventBus.post(new DatabaseOpened(dbName));
            return ds;
        } catch (IOException e) {
            throw new DatabaseNotCreatedException("Database not found: " + dbName, e);
        } catch (SQLException e) {
            throw new SQLRuntimeException("Database not initialized correctly: " + dbName, e);
        }
    }

    private String getDatastoreDirectory(String dbName) {
        return FilenameUtils.concat(this.path, dbName);
    }

    /**
     * <p>Returns the EventBus which this DatastoreManager posts
     * {@link com.cloudant.sync.notifications.DatabaseModified Database Notification Events} to.</p>
     * @return the DatastoreManager's EventBus
     *
     * @see <a href="https://code.google.com/p/guava-libraries/wiki/EventBusExplained">Google Guava EventBus documentation</a>
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    @Subscribe
    public void onDatabaseClosed(DatabaseClosed databaseClosed) {
        synchronized (openedDatastores) {
            this.openedDatastores.remove(databaseClosed.dbName);
        }
        this.eventBus.post(databaseClosed);
    }
}
