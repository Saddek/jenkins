package hudson.triggers;

import hudson.DependencyRunner;
import hudson.ExtensionPoint;
import hudson.DependencyRunner.ProjectRunnable;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.Describable;
import hudson.model.FingerprintCleanupThread;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.Project;
import hudson.model.WorkspaceCleanupThread;
import hudson.scheduler.CronTab;
import hudson.scheduler.CronTabList;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.Timer;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import antlr.ANTLRException;

/**
 * Triggers a {@link Build}.
 *
 * <p>
 * To register a custom {@link Trigger} from a plugin,
 * add it to {@link Triggers#TRIGGERS}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class Trigger<J extends Item> implements Describable<Trigger<?>>, ExtensionPoint {

    /**
     * Called when a {@link Trigger} is loaded into memory and started.
     *
     * @param project
     *      given so that the persisted form of this object won't have to have a back pointer.
     * @param newInstance
     *      True if this is a newly created trigger first attached to the {@link Project}.
     *      False if this is invoked for a {@link Project} loaded from disk.
     */
    public void start(J project, boolean newInstance) {
        this.job = project;
    }

    /**
     * Executes the triggered task.
     *
     * This method is invoked when {@link #Trigger(String)} is used
     * to create an instance, and the crontab matches the current time.
     */
    public void run() {}

    /**
     * Called before a {@link Trigger} is removed.
     * Under some circumstances, this may be invoked more than once for
     * a given {@link Trigger}, so be prepared for that.
     *
     * <p>
     * When the configuration is changed for a project, all triggers
     * are removed once and then added back.
     */
    public void stop() {}

    /**
     * Returns an action object if this {@link Trigger} has an action
     * to contribute to a {@link Project}.
     */
    public Action getProjectAction() {
        return null;
    }

    public abstract TriggerDescriptor getDescriptor();



    protected final String spec;
    protected transient CronTabList tabs;
    protected transient J job;

    /**
     * Creates a new {@link Trigger} that gets {@link #run() run}
     * periodically. This is useful when your trigger does
     * some polling work.
     */
    protected Trigger(String cronTabSpec) throws ANTLRException {
        this.spec = cronTabSpec;
        this.tabs = CronTabList.create(cronTabSpec);
    }

    /**
     * Creates a new {@link Trigger} without using cron.
     */
    protected Trigger() {
        this.spec = "";
        this.tabs = new CronTabList(Collections.<CronTab>emptyList());
    }

    /**
     * Gets the crontab specification.
     *
     * If you are not using cron service, just ignore it.
     */
    public final String getSpec() {
        return spec;
    }

    protected Object readResolve() throws ObjectStreamException {
        try {
            tabs = CronTabList.create(spec);
        } catch (ANTLRException e) {
            InvalidObjectException x = new InvalidObjectException(e.getMessage());
            x.initCause(e);
            throw x;
        }
        return this;
    }


    /**
     * Runs every minute to check {@link TimerTrigger} and schedules build.
     */
    private static class Cron extends SafeTimerTask {
        private final Calendar cal = new GregorianCalendar();

        public void doRun() {
            LOGGER.fine("cron checking "+cal.getTime().toLocaleString());

            try {
                checkTriggers(cal);
            } catch (Throwable e) {
                LOGGER.log(Level.WARNING,"Cron thread throw an exception",e);
                // bug in the code. Don't let the thread die.
                e.printStackTrace();
            }

            cal.add(Calendar.MINUTE,1);
        }
    }

    private static Future previousSynchronousPolling;

    public static void checkTriggers(final Calendar cal) {
        Hudson inst = Hudson.getInstance();

        // Are we using synchronous polling?
        if (SCMTrigger.DESCRIPTOR.synchronousPolling) {
            // Check that previous synchronous polling job is done to prevent piling up too many jobs
        	if (previousSynchronousPolling == null || previousSynchronousPolling.isDone()) {
	            // Process SCMTriggers in the order of dependencies. Note that the crontab spec expressed per-project is
	            // ignored, only the global setting is honored. The polling job is submitted only if the previous job has
	            // terminated.
	            // FIXME allow to set a global crontab spec
	            previousSynchronousPolling = SCMTrigger.DESCRIPTOR.getExecutor().submit(new DependencyRunner(new ProjectRunnable() {
	                public void run(AbstractProject p) {
	                    for (Trigger t : (Collection<Trigger>) p.getTriggers().values()) {
	                        if (t instanceof SCMTrigger)
	                            t.run();
	                    }
	                }
	            }));
        	}
        }

        // Process all triggers, except SCMTriggers when synchronousPolling is set
        for (AbstractProject<?,?> p : inst.getAllItems(AbstractProject.class)) {
            for (Trigger t : p.getTriggers().values()) {
                if (! (t instanceof SCMTrigger && SCMTrigger.DESCRIPTOR.synchronousPolling)) {
                    LOGGER.fine("cron checking "+p.getName());

                    if (t.tabs.check(cal)) {
                        LOGGER.fine("cron triggered "+p.getName());
                        t.run();
                    }
                }
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Trigger.class.getName());

    /**
     * This timer is available for all the components inside Hudson to schedule
     * some work.
     */
    public static final Timer timer = new Timer("Hudson cron thread");

    public static void init() {
        timer.scheduleAtFixedRate(new Cron(), 1000*60, 1000*60/*every minute*/);

        // clean up fingerprint once a day
        long HOUR = 1000*60*60;
        long DAY = HOUR*24;
        timer.scheduleAtFixedRate(new FingerprintCleanupThread(),DAY,DAY);
        timer.scheduleAtFixedRate(new WorkspaceCleanupThread(),DAY+4*HOUR,DAY);
    }
}
