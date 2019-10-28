/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.util.*;

import org.jocl.*;

/**
 * A small sample demonstrating basic event handling and how to
 * obtain profiling information for a command queue.
 */
public class JOCLEventSample
{
    /**
     * Source code of a kernel that adds the
     * components of two vectors and stores
     * the result in a third vector
     */
    private static String programSource0 =
        "__kernel void vectorAdd(" +
        "     __global const float *a,"+
        "     __global const float *b, " +
        "     __global float *c)"+
        "{"+
        "    int gid = get_global_id(0);"+
        "    c[gid] = a[gid]+b[gid];"+
        "}";

    /**
     * Source code of a kernel that multiplies the
     * components of two vectors and stores
     * the result in a third vector
     */
    private static String programSource1 =
        "__kernel void vectorMul(" +
        "     __global const float *a,"+
        "     __global const float *b, " +
        "     __global float *c)"+
        "{"+
        "    int gid = get_global_id(0);"+
        "    c[gid] = a[gid]*b[gid];"+
        "}";


    /**
     * The entry point of this sample
     *
     * @param args Not used
     */
    public static void main(String args[])
    {
        // Initialize the input data
        int n = 50000000;
        float srcArrayA[] = new float[n];
        float srcArrayB[] = new float[n];
        float dstArray0[] = new float[n];
        float dstArray1[] = new float[n];
        for (int i=0; i<srcArrayA.length; i++)
        {
            srcArrayA[i] = i;
            srcArrayB[i] = i;
        }
        Pointer srcA = Pointer.to(srcArrayA);
        Pointer srcB = Pointer.to(srcArrayB);
        Pointer dst0 = Pointer.to(dstArray0);
        Pointer dst1 = Pointer.to(dstArray1);

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
        cl_context context = clCreateContext(
            contextProperties, 1, new cl_device_id[]{device}, 
            null, null, null);
        
        // Create a command-queue, with profiling info enabled
        cl_queue_properties properties = new cl_queue_properties();
        properties.addProperty(CL_QUEUE_PROPERTIES, CL_QUEUE_PROFILING_ENABLE);
        System.out.println(properties);
        cl_command_queue commandQueue = clCreateCommandQueueWithProperties(
            context, device, properties, null);

        // Allocate the buffer memory objects
        cl_mem srcMemA = clCreateBuffer(context,
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            Sizeof.cl_float * n, srcA, null);

        cl_mem srcMemB = clCreateBuffer(context,
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            Sizeof.cl_float * n, srcB, null);

        cl_mem dstMem0 = clCreateBuffer(context,
            CL_MEM_READ_WRITE,
            Sizeof.cl_float * n, null, null);

        cl_mem dstMem1 = clCreateBuffer(context,
            CL_MEM_READ_WRITE,
            Sizeof.cl_float * n, null, null);

        // Create and build the the programs and the kernels
        cl_program program0 = clCreateProgramWithSource(context,
            1, new String[]{ programSource0 }, null, null);
        cl_program program1 = clCreateProgramWithSource(context,
            1, new String[]{ programSource1 }, null, null);

        // Build the programs
        clBuildProgram(program0, 0, null, null, null, null);
        clBuildProgram(program1, 0, null, null, null, null);

        // Create the kernels
        cl_kernel kernel0 = clCreateKernel(program0, "vectorAdd", null);
        cl_kernel kernel1 = clCreateKernel(program1, "vectorMul", null);

        // Set the arguments
        clSetKernelArg(kernel0, 0, Sizeof.cl_mem, Pointer.to(srcMemA));
        clSetKernelArg(kernel0, 1, Sizeof.cl_mem, Pointer.to(srcMemB));
        clSetKernelArg(kernel0, 2, Sizeof.cl_mem, Pointer.to(dstMem0));

        clSetKernelArg(kernel1, 0, Sizeof.cl_mem, Pointer.to(srcMemA));
        clSetKernelArg(kernel1, 1, Sizeof.cl_mem, Pointer.to(srcMemB));
        clSetKernelArg(kernel1, 2, Sizeof.cl_mem, Pointer.to(dstMem1));

        // Set work-item dimensions and execute the kernels
        long globalWorkSize[] = new long[]{n};

        System.out.println("Enqueueing kernels...");
        cl_event kernelEvent0 = new cl_event();
        clEnqueueNDRangeKernel(commandQueue, kernel0, 1, null,
            globalWorkSize, null, 0, null, kernelEvent0);

        cl_event kernelEvent1 = new cl_event();
        clEnqueueNDRangeKernel(commandQueue, kernel1, 1, null,
            globalWorkSize, null, 0, null, kernelEvent1);

        // Wait for the the events, i.e. until the kernels have completed
        System.out.println("Waiting for events...");
        clWaitForEvents(2, new cl_event[]{kernelEvent0, kernelEvent1});

        // Read the results
        System.out.println("Enqueueing output reads...");
        cl_event readEvent0 = new cl_event();
        clEnqueueReadBuffer(commandQueue, dstMem0, CL_TRUE, 0,
            n * Sizeof.cl_float, dst0, 0, null, readEvent0);

        cl_event readEvent1 = new cl_event();
        clEnqueueReadBuffer(commandQueue, dstMem1, CL_TRUE, 0,
            n * Sizeof.cl_float, dst1, 0, null, readEvent1);

        // Wait for the the events, i.e. until the results are read
        System.out.println("Waiting for events...");
        clWaitForEvents(2, new cl_event[]{readEvent0, readEvent1});

        // Print the results
        printResult(dstArray0, 10);
        printResult(dstArray1, 10);

        // Print the timing information for the commands
        ExecutionStatistics executionStatistics = new ExecutionStatistics();
        executionStatistics.addEntry("kernel0", kernelEvent0);
        executionStatistics.addEntry("kernel1", kernelEvent1);
        executionStatistics.addEntry("  read0", readEvent0);
        executionStatistics.addEntry("  read1", readEvent1);
        executionStatistics.print();

    }

    /**
     * Print up to 'max' entries of the given array
     *
     * @param result The array containing the result
     * @param max The maximum number of entries to print
     */
    private static void printResult(float result[], int max)
    {
        System.out.print("Result: ");
        max = Math.min(result.length, max);
        for (int i=0; i<max; i++)
        {
            System.out.print(result[i]);
            if (i < max-1)
            {
                System.out.print(", ");
            }
            else if (result.length > max)
            {
                System.out.print(" ...");
            }
        }
        System.out.println("");
    }

    /**
     * A simple helper class for tracking cl_events and printing
     * timing information for the execution of the commands that
     * are associated with the events.
     */
    static class ExecutionStatistics
    {
        /**
         * A single entry of the ExecutionStatistics
         */
        private static class Entry
        {
            private String name;
            private long submitTime[] = new long[1];
            private long queuedTime[] = new long[1];
            private long startTime[] = new long[1];
            private long endTime[] = new long[1];

            Entry(String name, cl_event event)
            {
                this.name = name;
                clGetEventProfilingInfo(
                    event, CL_PROFILING_COMMAND_QUEUED,
                    Sizeof.cl_ulong, Pointer.to(queuedTime), null);
                clGetEventProfilingInfo(
                    event, CL_PROFILING_COMMAND_SUBMIT,
                    Sizeof.cl_ulong, Pointer.to(submitTime), null);
                clGetEventProfilingInfo(
                    event, CL_PROFILING_COMMAND_START,
                    Sizeof.cl_ulong, Pointer.to(startTime), null);
                clGetEventProfilingInfo(
                    event, CL_PROFILING_COMMAND_END,
                    Sizeof.cl_ulong, Pointer.to(endTime), null);
            }

            void normalize(long baseTime)
            {
                submitTime[0] -= baseTime;
                queuedTime[0] -= baseTime;
                startTime[0] -= baseTime;
                endTime[0] -= baseTime;
            }

            long getQueuedTime()
            {
                return queuedTime[0];
            }

            void print()
            {
                System.out.println("Event "+name+": ");
                System.out.println("Queued : "+
                    String.format("%8.3f", queuedTime[0]/1e6)+" ms");
                System.out.println("Submit : "+
                    String.format("%8.3f", submitTime[0]/1e6)+" ms");
                System.out.println("Start  : "+
                    String.format("%8.3f", startTime[0]/1e6)+" ms");
                System.out.println("End    : "+
                    String.format("%8.3f", endTime[0]/1e6)+" ms");

                long duration = endTime[0]-startTime[0];
                System.out.println("Time   : "+
                    String.format("%8.3f", duration / 1e6)+" ms");
            }
        }

        /**
         * The list of entries in this instance
         */
        private List<Entry> entries = new ArrayList<Entry>();

        /**
         * Adds the specified entry to this instance
         *
         * @param name A name for the event
         * @param event The event
         */
        public void addEntry(String name, cl_event event)
        {
            entries.add(new Entry(name, event));
        }

        /**
         * Removes all entries
         */
        public void clear()
        {
            entries.clear();
        }

        /**
         * Normalize the entries, so that the times are relative
         * to the time when the first event was queued
         */
        private void normalize()
        {
            long minQueuedTime = Long.MAX_VALUE;
            for (Entry entry : entries)
            {
                minQueuedTime = Math.min(minQueuedTime, entry.getQueuedTime());
            }
            for (Entry entry : entries)
            {
                entry.normalize(minQueuedTime);
            }
        }

        /**
         * Print the statistics
         */
        public void print()
        {
            normalize();
            for (Entry entry : entries)
            {
                entry.print();
            }
        }


    }
}
