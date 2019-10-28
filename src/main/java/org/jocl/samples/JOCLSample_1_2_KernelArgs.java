/*
 * JOCL - Java bindings for OpenCL
 * 
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */
package org.jocl.samples;

import static org.jocl.CL.*;

import org.jocl.*;

/**
 * A small JOCL sample, demonstrating some of the new features
 * that have been introduced with OpenCL 1.2.
 */
public class JOCLSample_1_2_KernelArgs
{
    // The platform, device type and device number that will be used
    private static final int platformIndex = 0;
    private static final long deviceType = CL_DEVICE_TYPE_ALL;
    private static final int deviceIndex = 0;
    
    /**
     * The source code of the OpenCL program to execute
     */
    private static String programSource =
        "__kernel void "+"\n"+
        "sampleKernel(__global const volatile float *first,"+"\n"+
        "             __constant char *second,"+"\n"+
        "             __local unsigned int *third,"+"\n"+
        "             unsigned short fourth,"+"\n"+
        "             __write_only image2d_t fifth)"+"\n"+
        "{"+"\n"+
        "}"+"\n";

    private static cl_context context;
    private static cl_device_id device;

    /**
     * The entry point of this sample
     * 
     * @param args Not used
     */
    public static void main(String args[])
    {
        // Initialize the context and a device
        defaultInitialization();
        
        // Create the program from the source code
        cl_program program = clCreateProgramWithSource(context,
            1, new String[]{ programSource }, null, null);
        
        // Build the program. Note that the "-cl-kernel-arg-info" parameter
        // must be given, in order to keep the information about the kernel
        // arguments that will later be queried
        clBuildProgram(program, 0, null, "-cl-kernel-arg-info", null, null);
        
        // Create the kernel
        cl_kernel kernel = clCreateKernel(program, "sampleKernel", null);

        // Arrays that will store the parameter values
        int paramValueInt[] = { 0 };
        long paramValueLong[] = { 0 };
        long sizeArray[] = { 0 };
        byte paramValueCharArray[] = new byte[1024];
        
        // Obtain the number of arguments that the kernel has
        clGetKernelInfo(kernel, CL_KERNEL_NUM_ARGS, 
            Sizeof.cl_uint, Pointer.to(paramValueInt), null);
        int numArgs = paramValueInt[0];
        
        // Obtain information about each argument
        for (int a=0; a<numArgs; a++)
        {
            // The argument name
            clGetKernelArgInfo(kernel, a, CL_KERNEL_ARG_NAME, 
                0, null, sizeArray);
            clGetKernelArgInfo(kernel, a, CL_KERNEL_ARG_NAME, 
                sizeArray[0], Pointer.to(paramValueCharArray), null);
            String argName = 
                new String(paramValueCharArray, 0, (int)sizeArray[0]-1);
            
            // The address qualifier (global/local/constant/private)
            clGetKernelArgInfo(kernel, a, CL_KERNEL_ARG_ADDRESS_QUALIFIER,
                Sizeof.cl_int, Pointer.to(paramValueInt), null);
            int addressQualifier = paramValueInt[0];
            
            // The access qualifier (readOnly/writeOnly/readWrite/none)
            clGetKernelArgInfo(kernel, a, CL_KERNEL_ARG_ACCESS_QUALIFIER,
                Sizeof.cl_int, Pointer.to(paramValueInt), null);
            int accessQualifier = paramValueInt[0];
            
            // The type qualifier bitfield (const/restrict/volatile/none)
            clGetKernelArgInfo(kernel, a, CL_KERNEL_ARG_TYPE_QUALIFIER,
                Sizeof.cl_long, Pointer.to(paramValueLong), null);
            long typeQualifier = paramValueLong[0];
            
            // The type name
            clGetKernelArgInfo(kernel, a, CL_KERNEL_ARG_TYPE_NAME, 
                0, null, sizeArray);
            clGetKernelArgInfo(kernel, a, CL_KERNEL_ARG_TYPE_NAME, 
                sizeArray[0], Pointer.to(paramValueCharArray), null);
            String typeName = 
                new String(paramValueCharArray, 0, (int)sizeArray[0]-1);
            
            // Print the results:
            System.out.println("Argument "+a+":");
            System.out.println("    Name: "+argName);
            System.out.println("    Address qualifier: "+
                CL.stringFor_cl_kernel_arg_address_qualifier(addressQualifier));
            System.out.println("    Access qualifier : "+
                CL.stringFor_cl_kernel_arg_access_qualifier(accessQualifier));
            System.out.println("    Type qualifier   : "+
                CL.stringFor_cl_kernel_arg_type_qualifer(typeQualifier));
            System.out.println("    Type name        : "+typeName);
        }
    }
    
    /**
     * Default OpenCL initialization of the context, command queue,
     * program and kernel
     */
    private static void defaultInitialization()
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

        // Check if the platform supports OpenCL 1.2
        long sizeArray[] = { 0 };
        clGetPlatformInfo(platform, CL_PLATFORM_VERSION, 0, null, sizeArray);
        byte buffer[] = new byte[(int)sizeArray[0]];
        clGetPlatformInfo(platform, CL_PLATFORM_VERSION, 
            buffer.length, Pointer.to(buffer), null);
        String versionString = new String(buffer, 0, buffer.length-1);
        System.out.println("Platform version: "+versionString);
        String versionNumberString = versionString.substring(7, 10);
        try
        {
            String majorString = versionNumberString.substring(0, 1);
            String minorString = versionNumberString.substring(2, 3);
            int major = Integer.parseInt(majorString);
            int minor = Integer.parseInt(minorString);
            if (major == 1 && minor < 2)
            {
                System.err.println(
                    "Platform only supports OpenCL "+versionNumberString);
                System.exit(1);
            }
        }
        catch (NumberFormatException e)
        {
            System.err.println(
                "Invalid version number: "+versionNumberString);
            System.exit(1);
        }
        
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
        device = devices[deviceIndex];

        // Create a context for the selected device
        context = clCreateContext(
            contextProperties, 1, new cl_device_id[]{device}, 
            null, null, null);
    }
}
