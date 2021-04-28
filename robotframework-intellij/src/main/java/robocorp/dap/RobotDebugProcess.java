package robocorp.dap;

import com.intellij.execution.Executor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import robocorp.dap.stack.*;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * This is our debugger maestro... it takes care of actually connecting to the
 * debug adapter, making the robot launch, sending breakpoints, notifying
 * Intellij of thread suspension, etc.
 */
public class RobotDebugProcess extends XDebugProcess {

    private final XDebuggerEditorsProvider editorsProvider = new RobotDebuggerEditorsProvider();

    private final ProcessHandler processHandler;

    private final XBreakpointHandler<?>[] breakpointHandlers = new XBreakpointHandler[]{
            new RobotBreakpointHandler()
    };
    private final DAPDebugProtocolClient dapDebugProtocolClient;

    private final Capabilities capabilities;

    private final IDebugProtocolServer remoteProxy;

    private final DAPPositionConverter positionConverter = new DAPPositionConverter();

    private final Map<DAPSourcePosition, XBreakpoint> myRegisteredBreakpoints = new HashMap<>();

    private final ExecutorService singleThreadExecutor;

    public IDebugProtocolServer getRemoteProxy() {
        return remoteProxy;
    }

    public Collection<DAPThreadInfo> getThreads() {
        return dapDebugProtocolClient.getThreads();
    }

    public DAPStackFrame createStackFrame(DAPStackFrameInfo frameInfo) {
        final DAPStackFrame frame = new DAPStackFrame(getSession().getProject(), this, frameInfo,
                positionConverter.convertFromDAP(frameInfo.getPosition()));
        return frame;
    }

    private class RobotBreakpointHandler extends XBreakpointHandler<XLineBreakpoint<XBreakpointProperties>> {
        private final Map<String, List<XBreakpoint>> fileToBreakpoints = new HashMap<>();
        private final Object lock = new Object();

        protected RobotBreakpointHandler() {
            super(RobotLineBreakpoint.class);
        }

        @Override
        public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint) {
            XSourcePosition sourcePosition = breakpoint.getSourcePosition();
            VirtualFile file = sourcePosition.getFile();
            String path = file.getPath();
            synchronized (lock) {
                myRegisteredBreakpoints.put(positionConverter.convertToDAP(breakpoint.getSourcePosition()), breakpoint);
                List<XBreakpoint> breakpointList = fileToBreakpoints.get(path);
                if (breakpointList == null) {
                    breakpointList = new LinkedList<>();
                    fileToBreakpoints.put(path, breakpointList);
                }
                breakpointList.add(breakpoint);
                updateBreakpoints(path, breakpointList);
            }
        }

        @Override
        public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint, boolean temporary) {
            XSourcePosition sourcePosition = breakpoint.getSourcePosition();
            VirtualFile file = sourcePosition.getFile();
            String path = file.getPath();
            synchronized (lock) {
                myRegisteredBreakpoints.remove(positionConverter.convertToDAP(breakpoint.getSourcePosition()));
                List<XBreakpoint> breakpointList = fileToBreakpoints.get(path);
                if (breakpointList != null) {
                    breakpointList.remove(breakpoint);
                    updateBreakpoints(path, breakpointList);
                }
            }
        }

        private SetBreakpointsResponse updateBreakpoints(String path, List<XBreakpoint> breakpointsList) {
            SetBreakpointsArguments breakpointArgs = new SetBreakpointsArguments();
            Source source = new Source();
            File file = new File(path);
            source.setName(file.getName());
            source.setPath(file.getAbsolutePath());
            breakpointArgs.setSource(source);
            List<SourceBreakpoint> sourceBreakpointList = new ArrayList<>(breakpointsList.size());
            for (XBreakpoint b : breakpointsList) {
                SourceBreakpoint sourceBreakpoint = new SourceBreakpoint();
                XSourcePosition sourcePosition = b.getSourcePosition();
                DAPSourcePosition dapSourcePosition = positionConverter.convertToDAP(sourcePosition);
                sourceBreakpoint.setLine(dapSourcePosition.getLine());
                sourceBreakpointList.add(sourceBreakpoint);
            }
            breakpointArgs.setBreakpoints(sourceBreakpointList.toArray(new SourceBreakpoint[0]));
            CompletableFuture<SetBreakpointsResponse> future = remoteProxy.setBreakpoints(breakpointArgs);
            SetBreakpointsResponse setBreakpointsResponse = null;
            try {
                setBreakpointsResponse = future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // If breakpoints can't be set, we can't really recover!
                throw new RuntimeException(e);
            }
            return setBreakpointsResponse;
        }

    }

    protected RobotDebugProcess(Executor executor, @NotNull XDebugSession session, ProcessHandler processHandler) throws InterruptedException, ExecutionException, TimeoutException {
        super(session);
        this.processHandler = processHandler;
        session.setPauseActionSupported(false);

        // At this point we should've a process which started the debug adapter. Let's proceed and actually do the launch
        // for the target process.
        singleThreadExecutor = Executors.newSingleThreadExecutor();
        dapDebugProtocolClient = new DAPDebugProtocolClient(this, singleThreadExecutor);
        RobotRunProfileStateRobotDAPStarter.RobotProcessHandler baseProcessHandler = (RobotRunProfileStateRobotDAPStarter.RobotProcessHandler) processHandler;
        InputStream in = baseProcessHandler.getDebugAdapterProcess().getInputStream();
        OutputStream out = baseProcessHandler.getDebugAdapterProcess().getOutputStream();

        // Actually connect using the DAP with in/out streams.
        Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(dapDebugProtocolClient, in, out, false, null);
        launcher.startListening();
        InitializeRequestArguments arguments = new InitializeRequestArguments();
        arguments.setClientID("intellij");
        arguments.setAdapterID("RobotFramework");
        arguments.setPathFormat("path");
        arguments.setLinesStartAt1(true);
        arguments.setColumnsStartAt1(true);
        arguments.setSupportsRunInTerminalRequest(false);

        this.remoteProxy = launcher.getRemoteProxy();

        // If it's not initialized in 15 seconds, something is wrong (so, let the exception be thrown).
        this.capabilities = this.remoteProxy.initialize(arguments).get(15, TimeUnit.SECONDS);

        RobotRunProfileOptionsEditionAndPersistence runProfile = (RobotRunProfileOptionsEditionAndPersistence) session.getRunProfile();
        RobotLaunchConfigRunOptions options = runProfile.getOptions();

        String executorId = executor.getId();

        final boolean isDebug = ToolWindowId.DEBUG.equals(executorId);
        Map<String, Object> launchArgs = new HashMap<>();
        launchArgs.put("terminal", "none");

        launchArgs.put("target", options.target);
        launchArgs.put("args", options.args);
        launchArgs.put("cwd", options.computeWorkingDir());
        launchArgs.put("env", options.env);

        launchArgs.put("noDebug", !isDebug);
        launchArgs.put("__sessionId", "sessionId");
        CompletableFuture<Void> launch = this.remoteProxy.launch(launchArgs);
        // If it's not initialized in 15 seconds, something is wrong (so, let the exception be thrown).
        launch.get(15, TimeUnit.SECONDS);

    }

    @Override
    public void sessionInitialized() {
        super.sessionInitialized();
        ConsoleView consoleView = getSession().getConsoleView();
        if (consoleView != null) {
            RobotRunProfileOptionsEditionAndPersistence runProfile = (RobotRunProfileOptionsEditionAndPersistence) getSession().getRunProfile();
            RobotLaunchConfigRunOptions options = runProfile.getOptions();
            consoleView.print("Started: " + options.target + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        }
        remoteProxy.configurationDone(new ConfigurationDoneArguments());
        dapDebugProtocolClient.thread(null); // Call just to sync the current threads.
    }

    @Override
    public void stop() {
        if (singleThreadExecutor != null) {
            singleThreadExecutor.shutdown();
        }
        this.remoteProxy.terminate(new TerminateArguments());
    }

    @Override
    protected @Nullable ProcessHandler doGetProcessHandler() {
        return processHandler;
    }

    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return editorsProvider;
    }

    @Override
    public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
        return breakpointHandlers;
    }

    @NotNull
    protected DAPSuspendContext createSuspendContext(DAPThreadInfo threadInfo) {
        return new DAPSuspendContext(this, threadInfo);
    }

    @Override
    public void resume(@Nullable XSuspendContext context) {
        remoteProxy.continue_(new ContinueArguments());
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        remoteProxy.next(new NextArguments());
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        remoteProxy.stepIn(new StepInArguments());
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        remoteProxy.stepOut(new StepOutArguments());
    }

    public void threadSuspended(final DAPThreadInfo threadInfo) {
        final List<DAPStackFrameInfo> frames = threadInfo.getFrames();
        if (frames != null) {
            final DAPSuspendContext suspendContext = createSuspendContext(threadInfo);

            XBreakpoint<?> breakpoint = null;
            if (threadInfo.isStopOnBreakpoint()) {
                final DAPSourcePosition framePosition = frames.get(0).getPosition();
                breakpoint = myRegisteredBreakpoints.get(framePosition);
//                    if (breakpoint == null) {
//                        myDebugger.removeTempBreakpoint(position.getFile(), position.getLine());
//                    }
            } else if (threadInfo.isExceptionBreak()) {
//                    String exceptionName = threadInfo.getMessage();
//                    if (exceptionName != null) {
//                        breakpoint = myRegisteredExceptionBreakpoints.get(exceptionName);
//                    }
            }

            if (breakpoint != null) {
                boolean shouldSuspend = getSession().breakpointReached(breakpoint, threadInfo.getMessage(), suspendContext);
                if (!shouldSuspend) {
                    resume(suspendContext);
                } else {
                    ((XDebugSessionImpl) getSession()).positionReached(suspendContext, true);
                }
            } else {
                ((XDebugSessionImpl) getSession()).positionReached(suspendContext, false);
            }
        }
    }

}
