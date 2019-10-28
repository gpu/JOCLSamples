/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import org.jocl.*;

/**
 * A sample showing a simple reduction with JOCL
 */
public class JOCLReduction
{
    /**
     * The OpenCL context
     */
    private static cl_context context;
    
    /**
     * The OpenCL command queue to which the all work will be dispatched
     */
    private static cl_command_queue commandQueue;
    
    /**
     * The OpenCL program containing the reduction kernel
     */
    private static cl_program program;
    
    /**
     * The OpenCL kernel that performs the reduction
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
        
        // Create input array that will be reduced
        int n = 100000;
        float inputArray[] = new float[n];
        for (int i=0; i<n; i++)
        {
            inputArray[i] = i;
        }
        
        // Compute the reduction on the GPU and the CPU and print the results
        float resultGPU = reduce(inputArray);
        float resultCPU = reduceHost(inputArray);
        System.out.println("GPU "+resultGPU);
        System.out.println("CPU "+resultCPU);
        
        shutdown();
    }
    
    
    /**
     * Perform a reduction of the given input array on the GPU and return
     * the result. <br>
     * <br>
     * The reduction is performed in two phases: In the first phase, each
     * work group of the GPU computes the reduction of a part of the 
     * input array. The size of this part is exactly the number of work
     * items in the group, and the reduction will be performed in local
     * memory. The results of these reductions will be written into
     * an output array. This output array is then reduced on the CPU. 
     * 
     * @param inputArray The array on which the reduction will be performed
     * @return The result of the reduction
     */
    private static float reduce(float inputArray[])
    {
        int localWorkSize = 128;
        int numWorkGroups = 64;
        float outputArray[] = new float[numWorkGroups];

        // Allocate the memory objects for the input- and output data
        cl_mem inputMem = clCreateBuffer(context, 
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            Sizeof.cl_float * inputArray.length, Pointer.to(inputArray), null);
        cl_mem outputMem = clCreateBuffer(context, 
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            Sizeof.cl_float * numWorkGroups, Pointer.to(outputArray), null);

        // Perform the reduction on the GPU: Each work group will 
        // perform the reduction of 'localWorkSize' elements, and
        // the results will be written into the output memory
        reduce(
            inputMem, inputArray.length, 
            outputMem, numWorkGroups,
            localWorkSize);
        
        // Read the output data
        clEnqueueReadBuffer(commandQueue, outputMem, CL_TRUE, 0,
            numWorkGroups * Sizeof.cl_float, Pointer.to(outputArray), 
            0, null, null);

        // Perform the final reduction, by reducing the results 
        // from the work groups on the CPU
        float result = reduceHost(outputArray);
        
        // Release memory objects
        clReleaseMemObject(inputMem);
        clReleaseMemObject(outputMem);
        
        return result;
    }
    
    
    /**
     * Perform a reduction of the float elements in the given input memory.
     * Each work group will reduce 'localWorkSize' elements, and write the
     * result into the given output memory. 
     *  
     * @param inputMem The input memory containing the float values to reduce 
     * @param n The number of values in the input memory
     * @param outputMem The output memory that will store the reduction
     * result for each work group
     * @param numWorkGroups The number of work groups
     * @param localWorkSize The local work size, that is, the number of
     * work items in each work group 
     */
    private static void reduce(
        cl_mem inputMem, int n, 
        cl_mem outputMem, int numWorkGroups,
        int localWorkSize)
    {
        // Set the arguments for the kernel
        int a = 0;
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(inputMem));
        clSetKernelArg(kernel, a++, Sizeof.cl_float * localWorkSize, null);
        clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{n}));
        clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(outputMem));
        
        // Compute the number of work groups and the global work size
        long globalWorkSize = numWorkGroups * localWorkSize;
        
        // Execute the kernel
        clEnqueueNDRangeKernel(commandQueue, kernel, 1, null,
            new long[]{ globalWorkSize }, new long[]{ localWorkSize}, 
            0, null, null);
    }
    
    /**
     * Implementation of a Kahan summation reduction in plain Java
     * 
     * @param array The input 
     * @return The reduction result
     */
    private static float reduceHost(float array[])
    {
        float sum = array[0];
        float c = 0.0f;              
        for (int i = 1; i < array.length; i++)
        {
            float y = array[i] - c;  
            float t = sum + y;      
            c = (t - sum) - y;  
            sum = t;            
        }
        return sum;
    }
    
    /**
     * Initialize a default OpenCL context, command queue, program and kernel
     */
    private static void initialize()
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
        
        // Create the program from the source code
        String programSource = readFile("src/main/resources/kernels/reduction.cl");
        program = clCreateProgramWithSource(context,
            1, new String[]{ programSource }, null, null);
        
        // Build the program
        clBuildProgram(program, 0, null, null, null, null);
        
        // Create the kernel
        kernel = clCreateKernel(program, "reduce", null);
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
    
    /**
     * Read the contents of the file with the given name, and return
     * it as a string
     * 
     * @param fileName The name of the file to read
     * @return The contents of the file
     */
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
                catch (IOException ex)
                {
                    ex.printStackTrace();
                }
            }
        }
    }
    
}
