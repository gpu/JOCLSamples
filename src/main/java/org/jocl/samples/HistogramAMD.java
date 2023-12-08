/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.io.*;
import java.util.Random;

import org.jocl.*;

/**
 * This class is a port of the AMD OpenCL SDK "Histogram" sample.
 * The structure of the code has intentionally been kept similar 
 * to the original sample.  
 */
public class HistogramAMD
{
    public static final int SDK_SUCCESS = 0;
    public static final int SDK_FAILURE = 1;
    
    public static final int WIDTH = 1024;
    public static final int HEIGHT = 1024;
    public static final int BIN_SIZE = 256;
    public static final int GROUP_SIZE = 16;
    public static final int SUB_HISTOGRAM_COUNT = 
        ((WIDTH * HEIGHT) / (GROUP_SIZE * BIN_SIZE));
    
    /**
     * Entry point of this sample
     * 
     * @param args not used
     */
    public static void main(String args[])
    {
        /* Create Histogram object */
        HistogramAMD clHistogram = new HistogramAMD();
        
        /* Setup */
        if(clHistogram.setup()!=SDK_SUCCESS)
            return;

        /* Run */
        if(clHistogram.run()!=SDK_SUCCESS)
            return;

        /* Verify */
        if(clHistogram.verifyResults()!=SDK_SUCCESS)
            return;

        /* Cleanup resources created */
        if(clHistogram.cleanup()!=SDK_SUCCESS)
            return;

        /* Print performance statistics */
        clHistogram.printStats();
    }
    
    
    // Fields of the SDKSample base class

    String deviceType = "gpu";
    boolean timing = true;
    double totalTime;

    
    // Fields of the original Histogram class
    
    int binSize;        /**< Size of Histogram bin */
    int groupSize;      /**< Number of threads in group */
    int subHistgCnt;    /**< Sub histogram count */
    int data[];         /**< input data initialized with normalized(0 - binSize) random values */
    int width;          /**< width of the input */
    int height;         /**< height of the input */
    int hostBin[];      /**< Host result for histogram bin */
    int midDeviceBin[]; /**< Intermittent sub-histogram bins */
    int deviceBin[];    /**< Device result for histogram bin */

    double  setupTime;   /**< time taken to setup OpenCL resources and building kernel */
    double  kernelTime;  /**< time taken to run kernel and read result back */

    int maxWorkGroupSize[] = new int[1];        /**< Max allowed work-items in a group */
    int maxDimensions[] = new int[1];           /**< Max group dimensions allowed */
    long maxWorkItemSizes[] = new long[1];      /**< Max work-items sizes in each dimensions */
    long totalLocalMemory[] = new long[1];      /**< Max local memory allowed */
    long usedLocalMemory[] = new long[1];       /**< Used local memory */
        
    cl_context context;      /**< CL context */
    cl_device_id devices[];  /**< CL device list */
    
    cl_mem   dataBuf;          /**< CL memory buffer for data */
    cl_mem   midDeviceBinBuf;  /**< CL memory buffer for intermittent device bin */
    cl_mem   deviceBinBuf;     /**< CL memory buffer for deviceBin */

    cl_command_queue commandQueue;  /**< CL command queue */
    cl_program program;             /**< CL program  */
    cl_kernel kernel;               /**< CL kernel */

    /** 
     * Constructor 
     * Initialize member variables
     */
    public HistogramAMD()
    {
        binSize = BIN_SIZE;
        groupSize = GROUP_SIZE;
        setupTime = 0;
        kernelTime = 0;
        subHistgCnt = SUB_HISTOGRAM_COUNT;
        data = null;
        hostBin = null;
        midDeviceBin = null;
        deviceBin = null;
        devices = null;
        maxWorkItemSizes = null;

        /* Set default values for width and height */
        width = WIDTH;
        height = HEIGHT;
    }
    
    /**
     * Allocate and initialize required host memory with appropriate values
     * @return 0 on success and 1 on failure
     */
    int setupHistogram()
    {
        int i = 0;

        /* width must be multiples of binSize and
         * height must be multiples of groupSize
         */
        width = (width / binSize) * binSize;
        height = (height / groupSize) * groupSize;

        subHistgCnt = (width * height) / (groupSize * binSize);

        /* Allocate and init memory used by host */
        data = new int[width * height];

        Random random = new Random(0);
        for(i = 0; i < width * height; i++)
        {
            data[i] = random.nextInt(binSize);
        }

        hostBin = new int[binSize];
        midDeviceBin = new int[binSize * subHistgCnt];
        deviceBin = new int[binSize];
        
        return SDK_SUCCESS;
    }
    

    /**
     * OpenCL related initialisations. 
     * Set up Context, Device list, Command Queue, Memory buffers
     * Build CL kernel program executable
     * @return 0 on success and 1 on failure
     */
    int setupCL()
    {
        //CL.setLogLevel(LogLevel.LOG_DEBUGTRACE);

        int status[] = new int[1];
        long deviceListSize[] = new long[1];

        long dType;
        
        if(deviceType.compareTo("cpu") == 0)
        {
            dType = CL_DEVICE_TYPE_CPU;
        }
        else //deviceType = "gpu" 
        {
            dType = CL_DEVICE_TYPE_GPU;
        }

        // Obtain the platform IDs and initialize the context properties
        cl_platform_id platforms[] = new cl_platform_id[1];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platforms[0]);

        context = clCreateContextFromType(contextProperties, dType, null, null, status);
        
        /* 
         * if device is not set using command line arguments and opencl fails to open a 
         * context on default device GPU then it falls back to CPU 
         */
        if(status[0] != CL_SUCCESS && dType == CL_DEVICE_TYPE_GPU)
        {
            System.out.println("Unsupported GPU device; falling back to CPU ...");
            context = clCreateContextFromType(contextProperties, CL_DEVICE_TYPE_CPU, null, null, status);
        }
        
        // This allows to subsequently omit the error checks for this sample...
        CL.setExceptionsEnabled(true);

        /* First, get the size of device list data */
        status[0] = clGetContextInfo(context, CL_CONTEXT_DEVICES, 0, null, deviceListSize);

        /* Now allocate memory for device list based on the size we got earlier */
        devices = new cl_device_id[(int)deviceListSize[0] / Sizeof.cl_device_id];
        
        /* Now, get the device list data */
        status[0] = clGetContextInfo(context, CL_CONTEXT_DEVICES, deviceListSize[0], Pointer.to(devices), null);

        /* Check whether the device supports byte-addressable 
         * load/stores : required for Histogram */
        
        // Note that for JOCL this byte, NOT char!!!
        byte deviceExtensions[] = new byte[2048]; 
        
        /* Get device extensions */
        status[0] = clGetDeviceInfo(devices[0], CL_DEVICE_EXTENSIONS, 
            deviceExtensions.length, Pointer.to(deviceExtensions), null);

        /* Check if byte-addressable store is supported */
        String deviceExtensionsString = new String(deviceExtensions);
        if(!deviceExtensionsString.contains("cl_khr_byte_addressable_store"))
        {
            return SDK_FAILURE;
        }

        cl_queue_properties properties = new cl_queue_properties();
        if (timing)
        {
            properties.addProperty(CL_QUEUE_PROPERTIES, 
                CL_QUEUE_PROFILING_ENABLE);
        }
        commandQueue = clCreateCommandQueueWithProperties(
            context, devices[0], properties, null);

        /* Get Device specific Information */
        status[0] = clGetDeviceInfo(
            devices[0], CL_DEVICE_MAX_WORK_GROUP_SIZE, Sizeof.size_t,
            Pointer.to(maxWorkGroupSize), null);

        status[0] = clGetDeviceInfo(
            devices[0], CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS, Sizeof.cl_uint,
            Pointer.to(maxDimensions), null);

        maxWorkItemSizes = new long[maxDimensions[0]];
        status[0] = clGetDeviceInfo(
            devices[0], CL_DEVICE_MAX_WORK_ITEM_SIZES, Sizeof.size_t * maxDimensions[0],
                Pointer.to(maxWorkItemSizes), null);

        status[0] = clGetDeviceInfo(
            devices[0], CL_DEVICE_LOCAL_MEM_SIZE, Sizeof.cl_ulong,
            Pointer.to(totalLocalMemory), null);

        dataBuf = clCreateBuffer(
            context, CL_MEM_READ_ONLY | CL_MEM_USE_HOST_PTR, 
            Sizeof.cl_uint * width  * height, Pointer.to(data), status);

        midDeviceBinBuf = clCreateBuffer(
            context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, 
            Sizeof.cl_uint * binSize * subHistgCnt, null, status);

        /* create a CL program using the kernel source */
        String source = readFile("src/main/resources/kernels/Histogram_Kernels.cl");
        //System.out.println("source:\n"+source);
        program = clCreateProgramWithSource(
            context, 1, new String[]{source}, new long[] { source.length() }, status);

        /* create a cl program executable for all the devices specified */
        status[0] = clBuildProgram(program, 1, devices, null, null, null);

        /* get a kernel object handle for a kernel with the given name */
        kernel = clCreateKernel(program, "histogram256", status);

        return SDK_SUCCESS;
    }

    
    /**
     * Set values for kernels' arguments, enqueue calls to the kernels
     * on to the command queue, wait till end of kernel execution.
     * Get kernel start and end time if timing is enabled
     * @return 0 on success and 1 on failure
     */
    int runCLKernels()
    {
        int status[] = new int[1];
        cl_event events[] = new cl_event[] { new cl_event() }; // JOCL: Create an event!

        long globalThreads = (width * height) / binSize ;
        long localThreads = groupSize;

        if(localThreads > maxWorkItemSizes[0] || localThreads > maxWorkGroupSize[0])
        {
            System.out.println("Unsupported: Device does not support requested number of work items.");
            return SDK_FAILURE;
        }

        /* whether sort is to be in increasing order. CL_TRUE implies increasing */
        status[0] = clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(dataBuf)); 
        status[0] = clSetKernelArg(kernel, 1, groupSize * binSize * Sizeof.cl_uchar, null); 
        status[0] = clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(midDeviceBinBuf)); 

        status[0] = clGetKernelWorkGroupInfo(
            kernel, devices[0], CL_KERNEL_LOCAL_MEM_SIZE,
            Sizeof.cl_ulong, Pointer.to(usedLocalMemory), null);
        
        if(usedLocalMemory[0] > totalLocalMemory[0])
        {
            System.out.println("Unsupported: Insufficient local memory on device.");
            return SDK_FAILURE;
        }

        /* 
         * Enqueue a kernel run call.
         */
        status[0] = clEnqueueNDRangeKernel(
            commandQueue, kernel, 1, null, new long[]{globalThreads},
            new long[]{localThreads}, 0, null, events[0]);

        /* wait for the kernel call to finish execution */
        status[0] = clWaitForEvents(1, events);

        clReleaseEvent(events[0]);

        /* Enqueue the results to application pointer*/
        status[0] = clEnqueueReadBuffer(
            commandQueue, midDeviceBinBuf, CL_TRUE, 0,
            subHistgCnt * binSize * Sizeof.cl_uint,
            Pointer.to(midDeviceBin), 0, null, events[0]);
        
        /* wait for the read buffer to finish execution */
        status[0] = clWaitForEvents(1, events);

        clReleaseEvent(events[0]);

        /* Calculate final histogram bin */
        for(int i = 0; i < subHistgCnt; ++i)
        {
            for(int j = 0; j < binSize; ++j)
            {
                deviceBin[j] += midDeviceBin[i * binSize + j];
            }
        }

        return SDK_SUCCESS;
        
    }

    /**
     * Print sample stats.
     */
    void printStats()
    {
        /* calculate total time */
        totalTime = setupTime + kernelTime;

        System.out.println("Width     "+width);
        System.out.println("Height    "+height);
        System.out.println("setupTime(sec)  "+setupTime);
        System.out.println("kernelTime(sec) "+kernelTime);
        System.out.println("totalTime(sec)  "+totalTime);
    }


    /**
     * Adjust width and height 
     * of execution domain, perform all sample setup
     * @return 0 on success and 1 on failure
     */
    int setup()
    {
        if(setupHistogram()!=SDK_SUCCESS)
            return SDK_FAILURE;

        long before = System.nanoTime();
        
        if(setupCL()!=SDK_SUCCESS)
            return SDK_FAILURE;

        long after = System.nanoTime();

        /* Compute setup time */
        setupTime = (double)(after-before) / 1e9;

        return SDK_SUCCESS;
    }

    /**
     * Run OpenCL Histogram
     * @return 0 on success and 1 on failure
     */
    int run()
    {
        long before = System.nanoTime();

        /* Arguments are set and execution call is enqueued on command buffer */
        if(runCLKernels()!=SDK_SUCCESS)
            return SDK_FAILURE;

        long after = System.nanoTime();

        /* Compute kernel time */
        kernelTime = (double)(after-before) / 1e9;

        return SDK_SUCCESS;
        
    }

    /**
     * Cleanup memory allocations
     * @return 0 on success
     */
    int cleanup()
    {
        /* Releases OpenCL resources (Context, Memory etc.) */
        clReleaseMemObject(dataBuf);

        clReleaseMemObject(midDeviceBinBuf);

        clReleaseKernel(kernel);
        
        clReleaseProgram(program);

        clReleaseCommandQueue(commandQueue);

        clReleaseContext(context);

        return SDK_SUCCESS;
    }

    /**
     * Verify against reference implementation
     * @return 0 on success and 1 on failure
     */
    int verifyResults()
    {
        /* Reference implementation on host device
         * calculates the histogram bin on host
         */
        calculateHostBin();

        /* compare the results and see if they match */
        boolean result = true;
        for(int i = 0; i < binSize; ++i)
        {
            if(hostBin[i] != deviceBin[i])
            {
                result = false;
                break;
            }
        }

        if(result)
        {
            System.out.println("Passed!");
            return SDK_SUCCESS;
        }
        else
        {
            System.out.println("Failed");
            return SDK_FAILURE;
        }
    }


    /**
     *  Calculate histogram bin on host 
     */
    void calculateHostBin()
    {
        for(int i = 0; i < height; ++i)
        {
            for(int j = 0; j < width; ++j)
            {
                hostBin[data[i * width + j]]++;
            }
        }
    }
    
    
    
    private static String readFile(String fileName)
    {
        BufferedReader br = null;
        try
        {
            br = new BufferedReader(new FileReader(fileName));
            StringBuilder sb = new StringBuilder();
            String line = null;
            while (true)
            {
                line = br.readLine();
                if (line == null)
                {
                    break;
                }
                sb.append(line+"\n");
            }
            return sb.toString();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return "";
        }
        finally
        {
            if (br != null)
            {
                try
                {
                    br.close();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }
    
};











