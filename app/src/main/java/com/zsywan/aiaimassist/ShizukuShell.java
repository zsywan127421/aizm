
package com.zsywan.aiaimassist;

import android.os.ParcelFileDescriptor;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShizukuShell {
    public static String execCommand(String command) throws Exception {
        ParcelFileDescriptor pfd = Shizuku.execCommand(command, 0, null);
        if (pfd == null) {
            throw new Exception("Failed to execute command");
        }
        
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd))
        );
        
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }
        reader.close();
        
        return output.toString();
    }
}

