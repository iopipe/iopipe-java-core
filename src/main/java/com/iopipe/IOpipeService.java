package com.iopipe;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.iopipe.http.NullConnection;
import com.iopipe.http.RemoteBody;
import com.iopipe.http.RemoteConnection;
import com.iopipe.http.RemoteConnectionFactory;
import com.iopipe.http.RemoteException;
import com.iopipe.http.RemoteRequest;
import com.iopipe.http.RemoteResult;
import com.iopipe.http.RequestType;
import com.iopipe.plugin.IOpipePlugin;
import com.iopipe.plugin.IOpipePluginExecution;
import com.iopipe.plugin.IOpipePluginPostExecutable;
import com.iopipe.plugin.IOpipePluginPreExecutable;
import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import org.pmw.tinylog.Logger;

/**
 * This class provides a single connection to the IOpipe service which may then
 * be used to send multiple requests to as methods are ran. It is permittable
 * for this class to be used a singleton (and recommended for optimization
 * purposes) in which case you can call {@link IOpipeService#instance()} to
 * return a single instance of this class.
 *
 * It is recommended that code use the instance provided by
 * {@link IOpipeService#instance()}, then once an instance is obtained the
 * method {@link IOpipeService#run(Context, Function)} may be called.
 *
 * @since 2017/12/13
 */
public final class IOpipeService
{
	/** This is used to detect cold starts. */
	static final AtomicBoolean _THAWED =
		new AtomicBoolean();
	
	/** The time this class was loaded. */
	static final long _LOAD_TIME =
		IOpipeConstants.LOAD_TIME;
	
	/** The process stat when the process started. */
	static final SystemMeasurement.Times _STAT_START =
		SystemMeasurement.measureTimes(SystemMeasurement.SELF_PROCESS);
	
	/** Stores the execution for the current thread, inherited by child threads. */
	private static final ThreadLocal<Reference<IOpipeExecution>> _EXECUTIONS =
		new InheritableThreadLocal<>();
	
	/** Reference to the last execution that has occurred, just in case. */
	private static final AtomicReference<Reference<IOpipeExecution>> _LAST =
		new AtomicReference<>();
	
	/** If an instance was created then this will be that one instance. */
	private static volatile IOpipeService _INSTANCE;
	
	/** The configuration used to connect to the service. */
	protected final IOpipeConfiguration config;
	
	/** The connection to the server. */
	protected final RemoteConnection connection;
	
	/** Is the service enabled and working? */
	protected final boolean enabled;
	
	/** The coldstart flag indicator to use. */
	private final AtomicBoolean _coldstartflag;
	
	/** Plugin state. */
	final __Plugins__ _plugins;
	
	/** The number of times this context has been executed. */
	private final AtomicInteger _execcount =
		new AtomicInteger();
	
	/** The number of times the server replied with a code other than 2xx. */
	private final AtomicInteger _badresultcount =
		new AtomicInteger();
	
	/**
	 * Initializes the service using the default configuration.
	 *
	 * @since 2017/12/14
	 */
	public IOpipeService()
	{
		this(IOpipeConfiguration.DEFAULT_CONFIG);
	}
	
	/**
	 * Initializes the service using the specified configuration.
	 *
	 * @param __config The configuration to use.
	 * @throws NullPointerException On null arguments.
	 * @since 2017/12/24
	 */
	public IOpipeService(IOpipeConfiguration __config)
		throws NullPointerException
	{
		if (__config == null)
			throw new NullPointerException();
		
		// Try to open a connection to the IOpipe service, if that fails
		// then fall back to a disabled connection
		RemoteConnection connection = null;
		boolean enabled = false;
		if (__config.isEnabled())
			try
			{
				connection = __config.getRemoteConnectionFactory().connect(
					__config.getServiceUrl(), __config.getProjectToken());
				enabled = true;
			}
			
			// Cannot report error to IOpipe so print to the console
			catch (RemoteException e)
			{
				Logger.error(e, "Could not connect to the remote server.");
			}
		
		// If the connection failed, use one which does nothing
		if (!enabled || connection == null)
			connection = new NullConnection();
		
		this.enabled = enabled;
		this.connection = connection;
		this.config = __config;
		
		// Detect all available plugins
		this._plugins = new __Plugins__(enabled, __config);
		
		// Cold starts can either use the default global instance or they
		// can use a per-instance indicator. This is mostly used for testing.
		this._coldstartflag = (__config.getUseLocalColdStart() ?
			new AtomicBoolean() : IOpipeService._THAWED);
	}
	
	/**
	 * Returns the configuration which is used.
	 *
	 * @return The used configuration.
	 * @since 2017/12/20
	 */
	public final IOpipeConfiguration config()
	{
		return this.config;
	}
	
	/**
	 * Returns the number of requests which would have been accepted by the
	 * service if the configuration was correct and the service was enabled.
	 *
	 * @return The number of requests which would have been accepted by the
	 * server.
	 * @since 2017/12/18
	 */
	public final int getBadResultCount()
	{
		return this._badresultcount.get();
	}
	
	/**
	 * Is this service actually enabled?
	 *
	 * @return {@code true} if the service is truly enabled.
	 * @since 2017/12/17
	 */
	public final boolean isEnabled()
	{
		return this.enabled;
	}
	
	/**
	 * Runs the specified function and generates a report.
	 *
	 * @param <I> The input type.
	 * @param <O> The output type.
	 * @param __context The context provided by the AWS service.
	 * @param __func The lambda function to execute, measure, and generate a
	 * report for.
	 * @param __input The input value for the lambda.
	 * @return The result of the function.
	 * @throws Error If the called function threw an error.
	 * @throws NullPointerException If no function was specified.
	 * @throws RuntimeException If the called function threw an exception.
	 * @since 2018/08/09
	 */
	public final <I, O> O run(Context __context, RequestHandler<I, O> __func,
		I __input)
		throws Error, NullPointerException, RuntimeException
	{
		if (__func == null)
			throw new NullPointerException();
		
		// Use the context derived from the execution in the event that it is
		// changed
		return this.<O>run(__context, (__exec) -> __func.handleRequest(
			__input, __exec.context()), __input);
	}
	
	/**
	 * Runs the specified function and generates a report.
	 *
	 * @param __context The context provided by the AWS service.
	 * @param __func The lambda function to execute, measure, and generate a
	 * report for.
	 * @param __in The input stream for data.
	 * @param __out The output stream for data.
	 * @throws Error If the called function threw an error.
	 * @throws IOException On read/write errors.
	 * @throws NullPointerException If no function was specified.
	 * @throws RuntimeException If the called function threw an exception.
	 * @since 2018/08/09
	 */
	public final void run(Context __context, RequestStreamHandler __func,
		InputStream __in, OutputStream __out)
		throws Error, IOException, NullPointerException, RuntimeException
	{
		if (__func == null)
			throw new NullPointerException();
		
		// Use the context derived from the execution in the event that it is
		// changed
		try
		{
			this.<Object>run(__context, (__exec) ->
				{
					try
					{
						__func.handleRequest(__in, __out, __exec.context());
					}
					catch (IOException e)
					{
						throw new IOpipeWrappedException(
							e.getMessage(), e);
					}
					
					return null;
				}, __in);
		}
		
		// Forward IOExceptions
		catch (IOpipeWrappedException e)
		{
			throw (IOException)e.getCause();
		}
	}
	
	/**
	 * Runs the specified function and generates a report.
	 *
	 * @param <R> The value to return.
	 * @param __context The context provided by the AWS service.
	 * @param __func The lambda function to execute, measure, and generate a
	 * report for.
	 * @return The returned value.
	 * @throws Error If the called function threw an error.
	 * @throws NullPointerException If no function was specified.
	 * @throws RuntimeException If the called function threw an exception.
	 * @since 2017/12/14
	 */
	public final <R> R run(Context __context,
		Function<IOpipeExecution, R> __func)
		throws Error, NullPointerException, RuntimeException
	{
		return this.<R>run(__context, __func, null);
	}
	
	/**
	 * Runs the specified function and generates a report.
	 *
	 * @param <R> The value to return.
	 * @param __context The context provided by the AWS service.
	 * @param __func The lambda function to execute, measure, and generate a
	 * report for.
	 * @param __input An object which should specify the object which was
	 * input for the executed method, may be {@code null}.
	 * @return The returned value.
	 * @throws Error If the called function threw an error.
	 * @throws NullPointerException If no function was specified.
	 * @throws RuntimeException If the called function threw an exception.
	 * @since 2018/05/16
	 */
	public final <R> R run(Context __context,
		Function<IOpipeExecution, R> __func, Object __input)
		throws Error, NullPointerException, RuntimeException
	{
		if (__context == null || __func == null)
			throw new NullPointerException();
		
		// If an execution is already running, just ignore wrapping and
		// generating events and just call it directly
		{
			IOpipeExecution exec = IOpipeService.__execution();
			if (exec != null)
				return __func.apply(exec);
		}
		
		// Earliest start time for method entry
		long nowtime = System.currentTimeMillis(),
			nowmono = System.nanoTime();
		
		// Count executions
		int execcount = this._execcount.incrementAndGet();
		
		// Is this enabled?
		boolean enabled = config.isEnabled();
				
		// Is this coldstarted?
		boolean coldstarted = !this._coldstartflag.getAndSet(true);
		
		// Setup execution information
		IOpipeMeasurement measurement = new IOpipeMeasurement(coldstarted);
		IOpipeExecution exec = new IOpipeExecution(this, config, __context,
			measurement, nowtime, __input, nowmono);
		
		// Use a reference to allow the execution to be garbage collected if
		// it is no longer referred to or is in the stack of any method.
		// Otherwise execution references will just sit around in memory and
		// might not get freed ever.
		ThreadLocal<Reference<IOpipeExecution>> executions = _EXECUTIONS;
		Reference<IOpipeExecution> refexec = new WeakReference<>(exec);
		executions.set(refexec);
		
		// Just in case there was no way to get the current execution in the
		// event that the thread local could not be obtained
		AtomicReference<Reference<IOpipeExecution>> lastexec = _LAST;
		lastexec.compareAndSet(null, refexec);
		
		// If disabled, just run the function
		IOpipeConfiguration config = this.config;
		if (!enabled)
		{
			// Disabled lambdas could still rely on measurements, despite them
			// not doing anything useful at all
			this._badresultcount.incrementAndGet();
			
			try
			{
				return __func.apply(exec);
			}
			finally
			{
				// Clear the last execution because it is no longer occuring
				executions.set(null);
				lastexec.compareAndSet(refexec, null);
			}
		}
		
		Logger.debug("Invoking context {}.",
			() -> System.identityHashCode(__context));
		
		// Add auto-label for coldstart
		if (coldstarted)
			exec.label("@iopipe/coldstart");
		
		// Run pre-execution plugins
		__Plugins__.__Info__[] plugins = this._plugins.__info();
		for (__Plugins__.__Info__ i : plugins)
		{
			IOpipePluginPreExecutable l = i.getPreExecutable();
			if (l != null)
				try
				{
					exec.plugin(i.executionClass(), l::preExecute);
				}
				catch (RuntimeException e)
				{
					Logger.error(e, "Could not run pre-executable plugin {}.",
						i);
				}
		}
		
		// Register timeout with this execution number so if execution takes
		// longer than expected a timeout is generated
		// Timeouts can be disabled if the timeout window is zero, but they
		// may also be unsupported if the time remaining in the context is zero
		__TimeOutWatchDog__ watchdog = null;
		int windowtime;
		if ((windowtime = config.getTimeOutWindow()) > 0 &&
			__context.getRemainingTimeInMillis() > 0)
			watchdog = new __TimeOutWatchDog__(this, __context,
				Thread.currentThread(), windowtime, coldstarted, exec);
		
		// Run the function
		R value = null;
		Throwable exception = null;
		try
		{
			value = __func.apply(exec);
		}
		
		// An exception or error was thrown, so that will be reported
		// Error is very fatal, but still report that it occured
		catch (RuntimeException|Error e)
		{
			exception = e;
			
			measurement.__setThrown(e);
			measurement.addLabel("@iopipe/error");
		}
		
		// It died, so stop the watchdog
		if (watchdog != null)
			watchdog.__finished();
		
		// Run post-execution plugins
		for (__Plugins__.__Info__ i : plugins)
		{
			IOpipePluginPostExecutable l = i.getPostExecutable();
			if (l != null)
				try
				{
					exec.plugin(i.executionClass(), l::postExecute);
				}
				catch (RuntimeException e)
				{
					Logger.error(e, "Could not run post-executable plugin {}.",
						i);
				}
		}
		
		// Generate and send result to server
		if (watchdog == null || !watchdog._generated.getAndSet(true))
			this.__sendRequest(exec.__buildRequest());
		
		// Clear the last execution that is occuring, but only if ours was
		// still associated with it
		executions.set(null);
		lastexec.compareAndSet(refexec, null);
		
		// Throw the called exception as if the wrapper did not have any
		// trouble
		if (exception != null)
			if (exception instanceof Error)
				throw (Error)exception;
			else
				throw (RuntimeException)exception;
		return value;
	}
	
	/**
	 * Sends the specified request to the server.
	 *
	 * @param __r The request to send to the server.
	 * @return The result of the report.
	 * @throws NullPointerException On null arguments.
	 * @since 2017/12/15
	 */
	final RemoteResult __sendRequest(RemoteRequest __r)
		throws NullPointerException
	{
		if (__r == null)
			throw new NullPointerException();
		
		// Generate report
		try
		{
			RemoteResult result = this.connection.send(RequestType.POST, __r);
			
			// Only the 200 range is valid for okay responses
			int code = result.code();
			if (!(code >= 200 && code < 300))
			{
				this._badresultcount.incrementAndGet();
				
				// Only emit errors for failed requests
				Logger.error("Request {} failed with result {}.",
					__r, result);
			}
			
			return result;
		}
		
		// Failed to write to the server
		catch (RemoteException e)
		{
			Logger.error(e, "Request {} failed due to exception.", __r);
			
			this._badresultcount.incrementAndGet();
			return new RemoteResult(503, RemoteBody.MIMETYPE_JSON, "");
		}
	}
	
	/**
	 * Returns a single instance of the IOpipe service.
	 *
	 * @return The single instance of the service.
	 * @since 2017/12/20
	 */
	public static final IOpipeService instance()
	{
		IOpipeService rv = _INSTANCE;
		if (rv == null)
		{
			Logger.debug("Initializing new service instance.");
			
			_INSTANCE = (rv = new IOpipeService());
		}
		return rv;
	}
	
	/**
	 * Shows string representation of the body.
	 *
	 * @param __b The body to decode.
	 * @return The string result.
	 * @since 2018/02/24
	 */
	private static final String __debugBody(RemoteBody __b)
	{
		try
		{
			String rv = __b.bodyAsString();
			if (rv.indexOf('\0') >= 0)
				return "BINARY DATA";
			return rv;
		}
		catch (Throwable t)
		{
			return "Could not decode!";
		}
	}
	
	/**
	 * Returns the current execution of the current thread.
	 *
	 * @return The current execution or {@code null} if it could not obtained.
	 * @since 2018/07/30
	 */
	static final IOpipeExecution __execution()
	{
		Reference<IOpipeExecution> ref = _EXECUTIONS.get();
		IOpipeExecution rv;
		
		// If there is no thread local then use the last instance
		if (ref == null || null == (rv = ref.get()))
		{
			ref = _LAST.get();
			
			// No last execution exists either
			if (ref == null || null == (rv = ref.get()))
				return null;
		}
		
		// There was a thread local or last execution
		return rv;
	}
}

