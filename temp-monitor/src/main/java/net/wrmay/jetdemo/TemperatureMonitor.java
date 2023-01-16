package net.wrmay.jetdemo;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.aggregate.AggregateOperations;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.contrib.http.HttpListenerSources;
import com.hazelcast.jet.datamodel.KeyedWindowResult;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.datamodel.Tuple4;
import com.hazelcast.jet.datamodel.Tuple5;
import com.hazelcast.jet.pipeline.*;

public class TemperatureMonitor {

    private static String categorizeTemp(double temp, int warningLimit, int criticalLimit){
        String result;
        if (temp > (double) criticalLimit)
            result = "red";
        else if (temp > (double) warningLimit)
            result = "orange";
        else
            result = "green";

        return result;
    }

    public static Pipeline createPipeline(String logDir){
        Pipeline pipeline = Pipeline.create();

        StreamSource<MachineStatus> machineStatusEventSource =
                HttpListenerSources.httpListener(8080, MachineStatus.class);

        // create a stream of MachineStatus events from the map journal, use the timestamps embedded in the events
        StreamStage<MachineStatus> statusEvents = pipeline.readFrom(machineStatusEventSource)
                .withTimestamps(MachineStatus::getTimestamp, 2000)
                .setName("machine status events");


        statusEvents = LoggingService.tee(statusEvents, "status events", logDir, entry -> "NEW EVENT FOR " + entry.getSerialNum());

        // split the events by serial number, create a tumbling window to calculate avg. temp over 10s
        // output is a tuple: serial number, avg temp
        StreamStage<KeyedWindowResult<String, Double>> averageTemps = statusEvents.groupingKey(MachineStatus::getSerialNum)
                .window(WindowDefinition.tumbling(10000))
                .aggregate(AggregateOperations.averagingLong(MachineStatus::getBitTemp)).setName("Average Temp");

        averageTemps = LoggingService.tee(averageTemps, "average temps", logDir,   window -> "AVG " + window.getKey() + " " + window);

        // look up the machine profile for this machine, copy the warning temp onto the event
        // the output is serial number, avg temp, warning temp, critical temp
        StreamStage<Tuple4<String, Double, Integer, Integer>> temperaturesAndLimits = averageTemps.
                <String, MachineProfile, Tuple4<String, Double, Integer, Integer>>mapUsingIMap(Names.PROFILE_MAP_NAME,
                KeyedWindowResult::getKey,
                (window, machineProfile) -> Tuple4.tuple4(window.getKey(), window.getValue(), machineProfile.getWarningTemp(), machineProfile.getCriticalTemp()))
                .setName("Lookup Temp Limits");

        temperaturesAndLimits = LoggingService.tee(temperaturesAndLimits, "temps and limits", logDir,  tuple -> "LOOKUP " + tuple.f0() + " AVG: " + tuple.f1() + " WARN: " + tuple.f2() + " CRIT: " + tuple.f3());

        // categorize as GREEN / ORANGE / RED, add category to the end of the existing tuple
        StreamStage<Tuple5<String, Double, Integer, Integer, String>> labeledTemperatures = temperaturesAndLimits.map(tuple -> Tuple5.tuple5(tuple.f0(), tuple.f1(), tuple.f2(), tuple.f3(), categorizeTemp(tuple.f1(), tuple.f2(), tuple.f3())))
                .setName("Apply Label");

        labeledTemperatures = LoggingService.tee(labeledTemperatures, "labeled temps",logDir,  tuple -> "CATEGORIZE " + tuple.f0() + " " + tuple.f4());

        StreamStage<Tuple2<String, String>> statusChanges =
                labeledTemperatures.groupingKey(Tuple5::f0)
                        .filterStateful(Status::new, (status, item) -> status.checkAndSet(item.f4()))
                        .map( item -> Tuple2.tuple2(item.f0(), item.f4()));

        LoggingService.sink(statusChanges, "status changes", logDir,  entry -> "STATUS     " + entry.getKey() + " " + entry.getValue() );

        return pipeline;
    }
    public static void main(String []args){
        if (args.length != 1){
            System.err.println("Please provide the log output directory as the first argument");
            System.exit(1);
        }

        String logDir = args[0];

        Pipeline pipeline = createPipeline(logDir);

        JobConfig jobConfig = new JobConfig();
        jobConfig.setName("Temperature Monitor");
        HazelcastInstance hz = Hazelcast.bootstrappedInstance();
        hz.getJet().newJob(pipeline, jobConfig);
    }
}
