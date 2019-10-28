/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.util.Arrays;

import org.jocl.*;


/**
 * A sample demonstrating how to create sub-buffers
 * that have been introduced with OpenCL 1.1.
 */
public class JOCLSubBufferSample
{
    private static cl_context context;
    private static cl_command_queue commandQueue;

    /**
     * The entry point of this sample
     * 
     * @param args Not used
     */
    public static void main(String args[])
    {
        simpleInitialization();
        //CL.setLogLevel(LogLevel.LOG_TRACE);
        
        // Create an array with 8 elements and consecutive values
        int fullSize = 8;
        float fullArray[] = new float[fullSize];
        for (int i=0; i<fullSize; i++)
        {
            fullArray[i] = i;
        }
        System.out.println("Full input array  : "+Arrays.toString(fullArray));
        
        // Create a buffer for the full array
        cl_mem fullMem = clCreateBuffer(context, 
            CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, 
            Sizeof.cl_float * fullSize, Pointer.to(fullArray), null);

        // Create a sub-buffer
        int subOffset = 2;
        int subSize = 4;
        cl_buffer_region region = new cl_buffer_region(
            subOffset*Sizeof.cl_float, 
            subSize*Sizeof.cl_float);
        cl_mem subMem = clCreateSubBuffer(fullMem, 
            (int)CL_MEM_READ_WRITE, CL_BUFFER_CREATE_TYPE_REGION, 
            region, null);

        // Create an array for the sub-buffer, and copy the data
        // from the sub-buffer to the array
        float subArray[] = new float[subSize];
        clEnqueueReadBuffer(commandQueue, subMem, true, 
            0, subSize * Sizeof.cl_float, Pointer.to(subArray), 
            0, null, null);
        
        System.out.println("Read sub-array    : "+Arrays.toString(subArray));

        // Modify the data in the sub-array, and copy it back
        // into the sub-buffer
        subArray[0] = -5;
        subArray[1] = -4;
        subArray[2] = -3;
        subArray[3] = -2;
        clEnqueueWriteBuffer(commandQueue, subMem, true, 
            0, subSize * Sizeof.cl_float, Pointer.to(subArray), 
            0, null, null);

        System.out.println("Modified sub-array: "+Arrays.toString(subArray));
        
        // Read the full buffer back into the array 
        clEnqueueReadBuffer(commandQueue, fullMem, true, 
            0, fullSize * Sizeof.cl_float, Pointer.to(fullArray), 
            0, null, null);
        
        System.out.println("Full result array : "+Arrays.toString(fullArray));
        
    }
    
    
    /**
     * Simple OpenCL initialization of the context and command queue
     */
    private static void simpleInitialization()
    {
        // The platform, device type and device number
        // that will be used
        final int platformIndex = 0;
        final long deviceType = CL_DEVICE_TYPE_ALL;
        final int deviceIndex = 0;

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
        
        // Create a command-queue for the selected device
        cl_queue_properties properties = new cl_queue_properties();
        commandQueue = clCreateCommandQueueWithProperties(
            context, device, properties, null);
    }
    
    
}
