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
 * A small JOCL sample that uses multiple devices. <br>
 * <br>
 * Note: This is just a basic demo, showing the possibility to use multiple 
 * devices simultaneously. Each device receives its own copy of the memory 
 * objects to work on. In real applications, there may be a more complex
 * management of the buffers and the synchronization between the different 
 * devices, which is beyond the scope of this sample.
 */
public class JOCLMultiDeviceSample
{
    /**
     * The source code of the OpenCL program to execute, containing 
     * some artificial workload to compute
     */
    private static String programSource = 
        "__kernel void sampleKernel(__global const float *input,"+
        "                           __global float *output, " +
        "                           int size)"+
        "{"+
        "    int gid = get_global_id(0);"+
        "    output[gid] = 0;" +
        "    for (int i=0; i<size; i++) " +
        "        output[gid] += input[i];" +
        "}";
    

    /**
     * The entry point of this sample
     * 
     * @param args Not used
     */
    public static void main(String args[])
    {
        // Create input- and output data 
        int n = 10000;
        float input[] = new float[n];
        float output[] = new float[n];
        Arrays.fill(input, 1.0f);

        // The platform and device type that will be used
        final int platformIndex = 0;
        final long deviceType = CL_DEVICE_TYPE_ALL;

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
        String platformName = getString(platform, CL_PLATFORM_NAME);
        System.out.println("Using platform "+platformIndex+" of "+
            numPlatforms+": "+platformName);

        // Initialize the context properties
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);
        
        // Obtain the number of devices for the platform
        int numDevicesArray[] = new int[1];
        clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];
        
        // Obtain a device IDs 
        cl_device_id devices[] = new cl_device_id[numDevices];
        clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
        for (int i=0; i<numDevices; i++)
        {
            String deviceName = getString(devices[i], CL_DEVICE_NAME);
            System.out.println("Device "+i+" of "+numDevices+": "+deviceName);
        }

        // Create a context for the devices
        cl_context context = clCreateContext(
            contextProperties, devices.length, devices, null, null, null);

        // Create and build the program and the kernel
        cl_program program = clCreateProgramWithSource(context,
            1, new String[]{ programSource }, null, null);
        clBuildProgram(program, 0, null, null, null, null);
        cl_kernel kernel = clCreateKernel(program, "sampleKernel", null);

        // Allocate the memory objects for the input- and output data
        cl_mem inputMems[] = new cl_mem[numDevices];
        cl_mem outputMems[] = new cl_mem[numDevices];
        for (int i=0; i<numDevices; i++)
        {
            
            inputMems[i] = clCreateBuffer(context, 
                CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_float * n, Pointer.to(input), null);
            outputMems[i] = clCreateBuffer(context, 
                CL_MEM_READ_WRITE, 
                Sizeof.cl_float * n, null, null);
        }

        // Create one command-queue for each device
        cl_command_queue commandQueues[] = new cl_command_queue[numDevices];
        for (int i=0; i<numDevices; i++)
        {
            // Create the command queue
            cl_queue_properties properties = new cl_queue_properties();
            properties.addProperty(CL_QUEUE_PROPERTIES, 
                CL_QUEUE_PROFILING_ENABLE);
            commandQueues[i] = clCreateCommandQueueWithProperties(
                context, devices[i], properties, null);
        }
        
        // Execute the kernel on each command queue, and 
        // create events for each kernel launch
        long before = System.nanoTime();
        System.out.println("Enqueueing kernels");
        cl_event events[] = new cl_event[numDevices];
        for (int i=0; i<numDevices; i++)
        {
            clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(inputMems[i]));
            clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(outputMems[i]));
            clSetKernelArg(kernel, 2, Sizeof.cl_int, Pointer.to(new int[]{n}));
            
            events[i] = new cl_event();
            clEnqueueNDRangeKernel(commandQueues[i], kernel, 1, null,
                new long[]{n}, null, 0, null, events[i]);
        }
        
        // Wait until the work is finished on all command queues
        System.out.println("Waiting for kernels");
        clWaitForEvents(events.length, events);
        long after = System.nanoTime();
        
        // Print the duration for each device
        System.out.println("Waiting for kernels DONE");
        for (int i=0; i<numDevices; i++)
        {
            float durationMs = computeDurationMs(events[i]);
            System.out.println("Duration on device "+i+" of "+
                numDevices+": "+durationMs+"ms");
        }
        float totalDurationMs = (after-before)/1e6f;
        System.out.println("Total duration: "+totalDurationMs+"ms");
        
        
        // Read the output data of the first device
        clEnqueueReadBuffer(commandQueues[0], outputMems[0], CL_TRUE, 0,
            n * Sizeof.cl_float, Pointer.to(output), 0, null, null);
        
        // Release kernel, program, and memory objects
        clReleaseKernel(kernel);
        clReleaseProgram(program);
        for (int i=0; i<numDevices; i++)
        {
            clReleaseMemObject(inputMems[i]);
            clReleaseMemObject(outputMems[i]);
            clReleaseEvent(events[i]);
            clReleaseCommandQueue(commandQueues[i]);
        }
        clReleaseContext(context);
        
        // Print the first few elements of the result
        for (int i=0; i<10; i++)
        {
            float x = output[i];
            System.out.print(x+", ");
        }
        System.out.println("...");
        System.out.println("Done");
    }
    
    /**
     * Compute the execution duration of the given event, in milliseconds
     * 
     * @param event The event
     * @return The execution duration, in milliseconds
     */
    private static float computeDurationMs(cl_event event)
    {
        long startTime[] = {0};
        long endTime[] = {0};
        clGetEventProfilingInfo(
            event, CL_PROFILING_COMMAND_START,
            Sizeof.cl_ulong, Pointer.to(startTime), null);
        clGetEventProfilingInfo(
            event, CL_PROFILING_COMMAND_END,
            Sizeof.cl_ulong, Pointer.to(endTime), null);
        long durationNs = endTime[0]-startTime[0];
        return durationNs / 1e6f;
    }
    
    
    /**
     * Returns the value of the platform info parameter with the given name
     *
     * @param platform The platform
     * @param paramName The parameter name
     * @return The value
     */
    private static String getString(cl_platform_id platform, int paramName)
    {
        long size[] = new long[1];
        clGetPlatformInfo(platform, paramName, 0, null, size);
        byte buffer[] = new byte[(int)size[0]];
        clGetPlatformInfo(platform, paramName, 
            buffer.length, Pointer.to(buffer), null);
        return new String(buffer, 0, buffer.length-1);
    }
    
    /**
     * Returns the value of the device info parameter with the given name
     *
     * @param device The device
     * @param paramName The parameter name
     * @return The value
     */
    private static String getString(cl_device_id device, int paramName)
    {
        long size[] = new long[1];
        clGetDeviceInfo(device, paramName, 0, null, size);
        byte buffer[] = new byte[(int)size[0]];
        clGetDeviceInfo(device, paramName, 
            buffer.length, Pointer.to(buffer), null);
        return new String(buffer, 0, buffer.length-1);
    }
    
    
}
