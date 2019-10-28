/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;
import static org.jocl.CL.*;

import java.nio.ByteBuffer;
import java.util.Locale;

import org.jocl.*;

/**
 * A test for the bandwidth of of the data transfer from the host 
 * to the device. 
 */
public class JOCLBandwidthTest
{
    /**
     * The index of the OpenCL platform that this sample should run on
     */
    private static final int platformIndex = 0;
    
    /**
     * The OpenCL device type that will be used
     */
    private static final long deviceType = CL_DEVICE_TYPE_ALL;
    
    /**
     * The index of the OpenCL device that will be used
     */
    private static final int deviceIndex = 0;
    
    /**
     * The OpenCL context
     */
    private static cl_context context;
    
    /**
     * The OpenCL command queue
     */
    private static cl_command_queue commandQueue;

    /**
     * The host memory modes that will be tested
     */
    enum MemoryMode 
    { 
        PAGEABLE, 
        PINNED 
    }
    
    /**
     * The memory access modes that will be tested
     */
    enum AccessMode 
    { 
        MAPPED, 
        DIRECT 
    }

    /**
     * The number of memcopy operations to perform for each size
     */
    private static final long MEMCOPY_ITERATIONS = 100;    
    
    /**
     * The entry point of this sample
     * 
     * @param args Not used
     */
    public static void main(String args[])
    {
        initialize();
        
        for (MemoryMode memoryMode : MemoryMode.values())
        {
            for (AccessMode accessMode : AccessMode.values())
            {
                runTest(memoryMode, accessMode);
            }
        }
        
        shutdown();
    }
    
    /**
     * Run a bandwidth test with the given memory mode and access mode
     * 
     * @param memoryMode The memory mode
     * @param accessMode The access mode
     */
    private static void runTest(MemoryMode memoryMode, AccessMode accessMode)
    {
        int minExponent = 10;
        int maxExponent = 26;
        int count = maxExponent - minExponent;
        int memorySizes[] = new int[count];
        double bandwidths[] = new double[memorySizes.length];

        System.out.print("Running");
        for (int i=0; i<count; i++)
        {
            System.out.print(".");
            memorySizes[i] = (1 << minExponent + i);
            double bandwidth = computeBandwidth(
                memorySizes[i], memoryMode, accessMode);
            bandwidths[i] = bandwidth;
        }
        System.out.println();

        System.out.println("Bandwidths for "+memoryMode+" and "+accessMode);
        for (int i=0; i<memorySizes.length; i++)
        {
            String s = String.format("%10d", memorySizes[i]);
            String b = String.format(Locale.ENGLISH, "%5.3f", bandwidths[i]);
            System.out.println(s+" bytes : "+b+" MB/s");
        }
        System.out.println("\n");
    }


    /**
     * Compute the bandwidth in MB/s for copying a chunk of memory of 
     * the given size from the host to the device with the given 
     * memory- and access mode
     * 
     * @param memorySize The memory size, in bytes
     * @param memoryMode The memory mode
     * @param accessMode The access mode
     * @return The bandwidth, in MB/s
     */
    static double computeBandwidth(
        int memorySize, MemoryMode memoryMode, AccessMode accessMode)
    {
        ByteBuffer hostData = null;
        cl_mem pinnedHostData = null;
        cl_mem deviceData = null;

        if(memoryMode == MemoryMode.PINNED)
        {
            // Allocate pinned host memory
            pinnedHostData = clCreateBuffer(
                context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, 
                memorySize, null, null);

            // Map the buffer into the host address space
            hostData = clEnqueueMapBuffer(
                commandQueue, pinnedHostData, CL_TRUE, CL_MAP_WRITE, 
                0, memorySize, 0, null, null, null);

            // Write some data into the host buffer
            for(int i = 0; i < memorySize; i++)
            {
                hostData.put(i, (byte)i);
            }

            // Unmap the buffer, writing the data back to the
            // pinned host buffer
            clEnqueueUnmapMemObject(commandQueue, pinnedHostData, 
                hostData, 0, null, null);
        }
        else
        {
            // Standard (pageable, non-pinned) allocation
            hostData = ByteBuffer.allocateDirect(memorySize);

            // Write some data into the host buffer
            for(int i = 0; i < memorySize; i++)
            {
                hostData.put(i, (byte)i);
            }
        }

        // Allocate device memory
        deviceData = clCreateBuffer(
            context, CL_MEM_READ_WRITE, memorySize, null, null);

        clFinish(commandQueue);
        long before = System.nanoTime();

        if(accessMode == AccessMode.DIRECT)
        {
            if(memoryMode == MemoryMode.PINNED)
            {
                hostData = clEnqueueMapBuffer(
                    commandQueue, pinnedHostData, CL_TRUE, CL_MAP_READ, 
                    0, memorySize, 0, null, null, null);
            }

            // Copy the data from the host buffer to the
            // device a few times
            for(int i = 0; i < MEMCOPY_ITERATIONS; i++)
            {
                clEnqueueWriteBuffer(commandQueue, deviceData, CL_FALSE, 
                    0, memorySize, Pointer.to(hostData), 0, null, null);
            }
            clFinish(commandQueue);
        }
        else
        {
            // Map the data from the device to the host addess space
            ByteBuffer mappedDeviceData = clEnqueueMapBuffer(
                commandQueue, deviceData, CL_TRUE, CL_MAP_WRITE, 
                0, memorySize, 0, null, null, null);
            if(memoryMode == MemoryMode.PINNED )
            {
                hostData = clEnqueueMapBuffer(commandQueue, 
                    pinnedHostData, CL_TRUE, CL_MAP_READ, 0, 
                    memorySize, 0, null, null, null);
            }
            // Copy the data from the host buffer to the
            // device a few times
            for(int i = 0; i < MEMCOPY_ITERATIONS; i++)
            {
                mappedDeviceData.put(hostData);
                hostData.position(0);
                mappedDeviceData.position(0);
            }
            clEnqueueUnmapMemObject(commandQueue, deviceData, 
                mappedDeviceData, 0, null, null);
        }

        // Compute the bandwidth in MB/s
        long after = System.nanoTime();
        double durationS = (after - before) / 1e9;
        double bandwidthInMBs = 
            (memorySize * MEMCOPY_ITERATIONS)/(durationS * (1 << 20));

        // Clean up
        if(deviceData != null)
        {
            clReleaseMemObject(deviceData);
        }
        if(pinnedHostData != null)
        {
            clEnqueueUnmapMemObject(commandQueue, pinnedHostData, 
                hostData, 0, null, null);
            clReleaseMemObject(pinnedHostData);
        }

        return bandwidthInMBs;
    }    
    
    
    
    /**
     * Perform a default initialization by creating a context 
     * and a command queue
     */
    private static void initialize()
    {
        // Enable exceptions and subsequently omit error checks in this sample
        CL.setExceptionsEnabled(true);

        // Obtain the number of platforms
        int numPlatformsArray[] = new int[1];
        clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];

        // Obtain a platform ID
        cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id platform = platforms[platformIndex];

        // Initialize the context properties
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);
        
        // Obtain the number of devices for the platform
        int numDevicesArray[] = new int[1];
        clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];
        
        // Obtain a device ID 
        cl_device_id devices[] = new cl_device_id[numDevices];
        clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
        cl_device_id device = devices[deviceIndex];

        // Create a context for the selected device
        context = clCreateContext(
            contextProperties, 1, new cl_device_id[]{device}, 
            null, null, null);
        
        // Create the command queue
        cl_queue_properties properties = new cl_queue_properties();
        commandQueue = clCreateCommandQueueWithProperties(
            context, device, properties, null);
    }
    
    /**
     * Shut down and release all resources that have been allocated
     * in {@link #initialize()}
     */
    private static void shutdown()
    {
        clReleaseCommandQueue(commandQueue);
        clReleaseContext(context);
    }
}