package ru.cherdakmd.chrdkprofessions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;

/** Редкие commit/WAL-записи: force файла → atomic rename → force каталога. */
final class ProfessionDurableFile {
    static boolean write(Path file,String text,Logger log){
        Path target=file.toAbsolutePath(),temp=target.resolveSibling(target.getFileName()+".work-tmp");
        try{
            Files.createDirectories(target.getParent());
            Files.writeString(temp,text,StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(target.getParent());return true;
        }catch(IOException|RuntimeException failure){
            if(log!=null)log.severe("Не сохранён ремесленный commit "+target+": "+failure.getMessage());return false;
        }
    }
    static void delete(Path file)throws IOException{Files.delete(file);forceDirectory(file.toAbsolutePath().getParent());}
    private static void forceDirectory(Path directory)throws IOException{try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}}
    private ProfessionDurableFile(){}
}
