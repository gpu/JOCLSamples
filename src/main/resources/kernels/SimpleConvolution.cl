/*
 * JOCL - Java bindings for OpenCL
 *
 * Copyright 2009-2019 Marco Hutter - http://www.jocl.org/
 */

// A simple image convolution kernel

__kernel void convolution(
    __global uchar4 *input,
    __global float *mask,
    __global uchar4 *output,
    const int2 imageSize,
    const int2 maskSize,
    const int2 maskOrigin)
{
    int gx = get_global_id(0);
    int gy = get_global_id(1);

    if (gx >= maskOrigin.x &&
        gy >= maskOrigin.y &&
        gx < imageSize.x - (maskSize.x-maskOrigin.x-1) &&
        gy < imageSize.y - (maskSize.y-maskOrigin.y-1))
    {
        float4 sum = (float4)0;
        for(int mx=0; mx<maskSize.x; mx++)
        {
            for(int my=0; my<maskSize.x; my++)
            {
                int mi = mul24(my, maskSize.x) + mx;
                int ix = gx - maskOrigin.x + mx;
                int iy = gy - maskOrigin.y + my;
                int i = mul24(iy, imageSize.x) + ix;
                sum += convert_float4(input[i]) * mask[mi];
            }
        }
        uchar4 result = convert_uchar4_sat(sum);
        output[mul24(gy, imageSize.x)+gx] = result;
    }
    else
    {
        if (gx >= 0 && gx < imageSize.x &&
            gy >= 0 && gy < imageSize.y)
        {
            output[mul24(gy, imageSize.x)+gx] = (uchar4)0;
        }
    }

}


