package com.cloudant.sync.replication;

import com.cloudant.mazha.CouchConfig;
import com.cloudant.sync.datastore.Datastore;
import com.google.common.base.Preconditions;

import java.net.URI;

/**
 * <p>Provides configuration for a push replication.</p>
 *
 * <p>A push replication is <em>to</em> a remote Cloudant or CouchDB database
 * from the device's local datastore.</p>
 */
public class PushReplication extends Replication {

    /**
     * URI for this replication's remote database.
     */
    public URI target;
    /**
     * The local datastore for this replication.
     */
    public Datastore source;

    /**
     * Constructs a PushReplication object, configured by assigning to the
     * instance's attributes after construction.
     */
    public PushReplication() {
        /* Does nothing but we can now document it */
    }

    @Override
    void validate() {
        Preconditions.checkNotNull(this.target);
        Preconditions.checkNotNull(this.source);
        checkURI(this.target);
    }

    @Override
    String getReplicatorName() {
        return String.format("%s <-- %s ", target, source.getDatastoreName());
    }

    String getTargetDbName() {
        return this.extractDatabaseName(this.target);
    }

    CouchConfig getCouchConfig() {
        return this.createCouchConfig(this.target, this.username, this.password);
    }

    @Override
    ReplicationStrategy createReplicationStrategy() {
        return new BasicPushStrategy(this);
    }

}
