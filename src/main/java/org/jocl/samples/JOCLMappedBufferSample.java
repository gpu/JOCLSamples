/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.nio.*;

import org.jocl.*;

/**
 * A small JOCL sample, similar to the minimal JOCLSample, but
 * demonstrating how to map a cl_mem to a Java ByteBuffer
 */
public class JOCLMappedBufferSample
{
    /**
     * The source code of the OpenCL program to execute
     */
    private static String programSource =
        "__kernel void "+
        "sampleKernel(__global const float *a,"+
        "             __global const float *b,"+
        "             __global float *c)"+
        "{"+
        "    int gid = get_global_id(0);"+
        "    c[gid] = a[gid] * b[gid];"+
        "}";
    
    /**
     * The name of the kernel to execute
     */
    private static final String kernelName = "sampleKernel";

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
     * The OpenCL program that contains the kernel
     */
    private static cl_program program;
    
    /**
     * The OpenCL kernel from the program
     */
    private static cl_kernel kernel;
    
    /**
     * The entry point of this sample
     * 
     * @param args Not used
     */
    public static void main(String args[])
    {
        initialize();
        
        // Create input- and output data 
        int n = 10;
        float srcArrayA[] = new float[n];
        float srcArrayB[] = new float[n];
        float dstArray[] = new float[n];
        for (int i=0; i<n; i++)
        {
            srcArrayA[i] = i;
            srcArrayB[i] = i;
        }
        
        // Allocate the memory objects for the input- and output data
        cl_mem srcMemA = clCreateBuffer(context, 
            CL_MEM_READ_WRITE | CL_MEM_USE_HOST_PTR,
            Sizeof.cl_float * n, Pointer.to(srcArrayA), null);
        cl_mem srcMemB = clCreateBuffer(context, 
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            Sizeof.cl_float * n, Pointer.to(srcArrayB), null);
        cl_mem dstMem = clCreateBuffer(context, 
            CL_MEM_READ_WRITE, Sizeof.cl_float * n, null, null);
        
        // Create a mapped buffer, which allows direct access to the cl_mem
        // contents that was created from srcArrayA 
        ByteBuffer mapped = clEnqueueMapBuffer(commandQueue, srcMemA, 
            true, CL_MAP_WRITE, 0, n * Sizeof.cl_float, 0, null, null, null);
        FloatBuffer floatBuffer = 
            mapped.order(ByteOrder.nativeOrder()).asFloatBuffer();
        
        // Modify the contents of the cl_mem by changing some values
        // in the mapped buffer
        floatBuffer.put(4, 40);
        floatBuffer.put(5, 50);
        floatBuffer.put(6, 60);
        
        // Unmap the buffer
        clEnqueueUnmapMemObject(commandQueue, srcMemA, 
            mapped, 0, null, null);
        
        
        // Set the arguments for the kernel
        int a = 0;
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(srcMemA));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(srcMemB));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(dstMem));
        
        // Execute the kernel
        clEnqueueNDRangeKernel(commandQueue, kernel, 1, null,
            new long[]{n}, null, 0, null, null);
        
        // Read the output data
        clEnqueueReadBuffer(commandQueue, dstMem, CL_TRUE, 0,
            n * Sizeof.cl_float, Pointer.to(dstArray), 0, null, null);
        
        // Release the memory objects
        clReleaseMemObject(srcMemA);
        clReleaseMemObject(srcMemB);
        clReleaseMemObject(dstMem);
        
        // Verify the result. Before, apply the changes that have been done 
        // for the cl_mem of srcArrayA via the mapped buffer.
        srcArrayA[4] = 40;
        srcArrayA[5] = 50;
        srcArrayA[6] = 60;
        boolean passed = true;
        final float epsilon = 1e-7f;
        for (int i=0; i<n; i++)
        {
            float x = dstArray[i];
            float y = srcArrayA[i] * srcArrayB[i];
            boolean epsilonEqual = Math.abs(x - y) <= epsilon * Math.abs(x);
            if (!epsilonEqual)
            {
                passed = false;
                break;
            }
        }
        System.out.println("Test "+(passed?"PASSED":"FAILED"));
        if (n <= 10)
        {
            System.out.println("Result: "+java.util.Arrays.toString(dstArray));
        }
        
        shutdown();
    }
    
    /**
     * Perform a default initialization by creating a context 
     * and a command queue, building the program and obtaining
     * the kernel. 
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
        
        // Create the program
        program = clCreateProgramWithSource(context, 
            1, new String[]{ programSource }, null, null);
        
        // Build the program
        clBuildProgram(program, 0, null, null, null, null);
        
        // Create the kernel
        kernel = clCreateKernel(program, kernelName, null);
    }
    
    /**
     * Shut down and release all resources that have been allocated
     * in {@link #initialize()}
     */
    private static void shutdown()
    {
        clReleaseKernel(kernel);
        clReleaseProgram(program);
        clReleaseCommandQueue(commandQueue);
        clReleaseContext(context);
    }
}
