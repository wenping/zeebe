package org.camunda.tngp.broker.logstreams.cfg;

import org.camunda.tngp.broker.system.ComponentConfiguration;
import org.camunda.tngp.broker.system.GlobalConfiguration;
import org.camunda.tngp.util.FileUtil;

public class SnapshotStorageCfg extends ComponentConfiguration
{

    public String directory;

    @Override
    protected  void onApplyingGlobalConfiguration(GlobalConfiguration global)
    {

        this.directory = (String) new Rules("first")
             .setGlobalObj(global.directory)
             .setLocalObj(directory, "directory")
             .setRule((r) ->
             { return r + "snapshot/"; }).execute();

        this.directory = FileUtil.getCanonicalDirectoryPath(this.directory);

    }

}
