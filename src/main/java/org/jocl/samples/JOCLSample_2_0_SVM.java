/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.nio.*;
import java.util.Arrays;

import org.jocl.*;

/**
 * A JOCL sample demonstrating the SVM (shared virtual memory)
 * functionality that has been introduced with OpenCL 2.0
 */
public class JOCLSample_2_0_SVM
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
    
    private static cl_context context;
    private static cl_device_id device;
    private static cl_command_queue commandQueue;
    private static cl_kernel kernel;
    
    public static void main(String[] args)
    {
        initCL();
        
        // Create input- and output data 
        int n = 10;
        float srcArrayA[] = new float[n];
        float srcArrayB[] = new float[n];
        for (int i=0; i<n; i++)
        {
            srcArrayA[i] = i;
            srcArrayB[i] = i;
        }
        Pointer srcA = Pointer.to(srcArrayA);
        Pointer srcB = Pointer.to(srcArrayB);

        // Allocate the memory objects for the input data
        cl_mem srcMemA = clCreateBuffer(context, 
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            Sizeof.cl_float * n, srcA, null);
        cl_mem srcMemB = clCreateBuffer(context, 
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            Sizeof.cl_float * n, srcB, null);
        
        // Allocate shared virtual memory
        Pointer svm = clSVMAlloc(context, 
            CL_MEM_READ_WRITE, Sizeof.cl_float * n, 0);
       
        // Set the arguments for the kernel
        int a = 0;
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(srcMemA));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(srcMemB));
        clSetKernelArgSVMPointer(kernel, a++, svm);
        
        // Execute the kernel
        clEnqueueNDRangeKernel(commandQueue, kernel, 1, 
            null, new long[]{n}, null, 0, null, null);
        
        // Enqueue the command to map the SVM into the host
        // memory space
        clEnqueueSVMMap(commandQueue, true, CL_MAP_WRITE, 
            svm, Sizeof.cl_float * n, 0, null, null);
        
        // Obtain the contents of the SVM as a FloatBuffer
        FloatBuffer fb = svm.getByteBuffer(0, Sizeof.cl_float * n).
            order(ByteOrder.nativeOrder()).asFloatBuffer();

        // Print the contents of the SVM, and modify it
        for (int i=0; i<n; i++)
        {
            float fOld = fb.get(i);
            float fNew = i+i;
            System.out.println("At "+i+" got "+fOld+", setting "+fNew);
            fb.put(i, fNew);
        }
        
        // Enqueue the command to un-map the SVM
        clEnqueueSVMUnmap(commandQueue, svm, 0, null, null);
        
        // Create output memory
        cl_mem dstMem = clCreateBuffer(context, 
            CL_MEM_READ_WRITE, 
            Sizeof.cl_float * n, null, null);
        
        // Execute the kernel again, reading from the SVM
        // and writing into the output memory this time
        a = 0;
        clSetKernelArgSVMPointer(kernel, a++, svm);
        clSetKernelArgSVMPointer(kernel, a++, svm);
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(dstMem));
        clEnqueueNDRangeKernel(commandQueue, kernel, 1, 
            null, new long[]{n}, null, 0, null, null);
        
        // Read the output data
        float dstArray[] = new float[n];
        Pointer dst = Pointer.to(dstArray);
        clEnqueueReadBuffer(commandQueue, dstMem, CL_TRUE, 0,
            n * Sizeof.cl_float, dst, 0, null, null);
        
        // Enqueue the command to free the SVM data,
        // registering an example callback
        SVMFreeFunction callback = new SVMFreeFunction()
        {
            @Override
            public void function(cl_command_queue queue, 
                int num_svm_pointers, Pointer[] svm_pointers, Object user_data)
            {
                System.out.println(
                    "Callback for freeing "+ num_svm_pointers+
                    " SVM pointers, user data is "+user_data);
            }
        };
        Object userData = "SampleUserData";
        clEnqueueSVMFree(commandQueue, 1, new Pointer[]{svm}, 
            callback, userData, 0, null, null);

        clFinish(commandQueue);
        
        // Release kernel, program, and memory objects
        clReleaseMemObject(srcMemA);
        clReleaseMemObject(srcMemB);
        clReleaseMemObject(dstMem);
        clReleaseKernel(kernel);
        clReleaseCommandQueue(commandQueue);
        clReleaseContext(context);
        
        // Verify the result
        boolean passed = true;
        final float epsilon = 1e-7f;
        for (int i=0; i<n; i++)
        {
            float x = dstArray[i];
            float y = 
                (srcArrayA[i]+srcArrayA[i]) * 
                (srcArrayB[i]+srcArrayB[i]);
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
            System.out.println("Result: "+Arrays.toString(dstArray));
        }
        
    }
    
    
    /**
     * Default OpenCL initialization of the devices, context,
     * command queue, program and kernel.
     */
    private static void initCL()
    {
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
        
        // Initialize the context properties
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);
        
        // Obtain the number of devices for the platform
        int numDevicesArray[] = new int[1];
        clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];
        
        // Obtain the all device IDs 
        cl_device_id allDevices[] = new cl_device_id[numDevices];
        clGetDeviceIDs(platform, deviceType, numDevices, allDevices, null);


        // Find the first device that supports OpenCL 2.0
        for (cl_device_id currentDevice : allDevices)
        {
            String deviceName = getString(currentDevice, CL_DEVICE_NAME);
            float version = getOpenCLVersion(currentDevice);
            if (version >= 2.0)
            {
                System.out.println("Using device "+
                    deviceName+", version "+version);
                device = currentDevice;
                break;
            }
            else
            {
                System.out.println("Skipping device "+
                    deviceName+", version "+version);
            }
        }
        if (device == null)
        {
            System.out.println("No OpenCL 2.0 capable device found");
            System.exit(1);
        }
        
        // Create a context 
        context = clCreateContext(
            contextProperties, 1, new cl_device_id[]{ device }, 
            null, null, null);
        
        // Create the command queue
        cl_queue_properties properties = new cl_queue_properties();
        commandQueue = clCreateCommandQueueWithProperties(
            context, device, properties, null);

        // Create the program from the source code
        cl_program program = clCreateProgramWithSource(context,
            1, new String[]{ programSource }, null, null);
        
        // Build the program. It's important to specify the
        // -cl-std=CL2.0
        // build parameter here!
        clBuildProgram(program, 0, null, "-cl-std=CL2.0", null, null);
        
        // Create the kernel
        kernel = clCreateKernel(program, "sampleKernel", null);
        
        clReleaseProgram(program);
    }
    
    /**
     * Returns the OpenCL version of the given device, as a float
     * value
     * 
     * @param device The device
     * @return The OpenCL version
     */
    private static float getOpenCLVersion(cl_device_id device)
    {
        String deviceVersion = getString(device, CL_DEVICE_VERSION);
        String versionString = deviceVersion.substring(7, 10);
        float version = Float.parseFloat(versionString);
        return version;
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
        // Obtain the length of the string that will be queried
        long size[] = new long[1];
        clGetDeviceInfo(device, paramName, 0, null, size);

        // Create a buffer of the appropriate size and fill it with the info
        byte buffer[] = new byte[(int)size[0]];
        clGetDeviceInfo(device, paramName, buffer.length, Pointer.to(buffer), null);

        // Create a string from the buffer (excluding the trailing \0 byte)
        return new String(buffer, 0, buffer.length-1);
    }
    
    
}
