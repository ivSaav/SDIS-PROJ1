package main.g06;

public class Definitions {

        public static String STORAGE_DIR = "storage";
        public static int CHUNK_SIZE = 64000;
        public enum Operation {
                BACKUP, RESTORE, DELETE, RECLAIM, STORE, STATE
        }
}
