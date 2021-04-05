package main.g06;

import main.g06.message.Message;
import main.g06.message.MessageType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;


public class Peer implements ClientPeerProtocol {

    private final int id;
    private final String version;
    private int disk_usage; //disk usage in KBytes
    private final Map<String, FileDetails> fileHashes; // filename --> FileDetail
    private final Map<String, FileDetails> fileDetails; // filehash --> FileDetail
    private final Map<String, Set<Chunk>> storedChunks; // filehash --> Chunks

    private final MulticastChannel backupChannel;
    private final MulticastChannel controlChannel;

    public Peer(String version, int id, String MC, String MDB, String MDR) {
        this.id = id;
        this.version = version;
        this.disk_usage = 0;
        // TODO: Reload this data from disk
        this.fileHashes = new HashMap<>();
        this.fileDetails = new HashMap<>();
        this.storedChunks = new HashMap<>();

        String[] vals = MC.split(":"); //MC

        String mcAddr = vals[0];
        int mcPort = Integer.parseInt(vals[1]);

        vals = MDB.split(":");
        String mdbAddr = vals[0];
        int mdbPort = Integer.parseInt(vals[1]);

        vals = MDR.split(":");
        String mdrAddr = vals[0];
        int mdrPort = Integer.parseInt(vals[1]);

        // TODO: Use custom ThreadPoolExecutor to maximize performance
        this.backupChannel = new MulticastChannel(this, mdbAddr, mdbPort, (ThreadPoolExecutor) Executors.newFixedThreadPool(10));
        this.controlChannel = new MulticastChannel(this, mcAddr, mcPort, (ThreadPoolExecutor) Executors.newFixedThreadPool(10));
    }

    @Override
    public String backup(String path, int repDegree) {

        String fileHash = SdisUtils.createFileHash(path);
        if (fileHash == null)
            return "failure";

        try {
            File file = new File(path);
            int num_chunks = (int) Math.floor( (double) file.length() / (double) Definitions.CHUNK_SIZE) + 1;

            // Save filename and its generated hash
            // TODO: Write to file in case peer crashes we must resume this operation
            FileDetails fd = new FileDetails(fileHash, file.length(), repDegree);
            this.fileHashes.put(path, fd);
            this.fileDetails.put(fileHash, fd);

            FileInputStream fstream = new FileInputStream(file);
            byte[] chunk_data = new byte[Definitions.CHUNK_SIZE];
            int num_read, last_num_read = -1, chunkNo = 0;

            // Read file chunks and send them
            while ((num_read = fstream.read(chunk_data)) != -1) {
                // Send message
                byte[] message = num_read == Definitions.CHUNK_SIZE ?
                        Message.createMessage(this.version, MessageType.PUTCHUNK, this.id, fileHash, chunkNo, repDegree, chunk_data)
                        : Message.createMessage(this.version, MessageType.PUTCHUNK, this.id, fileHash, chunkNo, repDegree, Arrays.copyOfRange(chunk_data, 0, num_read));
                backupChannel.multicast(message, message.length);
                System.out.printf("MDB: chunkNo %d ; size %d\n", chunkNo, num_read);

                // TODO: Repeat message N times if rep degree was not reached
                // TODO: Advance chunk only after rep degree was reached

                last_num_read = num_read;
                chunkNo++;
            }

            // Case of last chunk being size 0
            if (last_num_read == Definitions.CHUNK_SIZE) {
                byte[] message = Message.createMessage(this.version, MessageType.PUTCHUNK, this.id, fileHash, chunkNo, repDegree, new byte[] {});
                backupChannel.multicast(message, message.length);
                System.out.printf("MDB: chunkNo %d ; size %d\n", chunkNo, num_read);
            }

            fstream.close();
            System.out.println("Created " + num_chunks + " chunks");
        }
        catch (IOException e) {
            e.printStackTrace();
            return "failure";
        }

        return "success";
    }

    @Override
    public String delete(String file) {
        // checking if this peer was the initiator for file backup
        if (this.fileHashes.containsKey(file)) {
            // send delete message to other peers
            FileDetails fileInfo = fileHashes.get(file);
            byte[] message = Message.createMessage(this.version, MessageType.DELETE, this.id, fileInfo.getHash());
            controlChannel.multicast(message, message.length);
            System.out.printf("DELETE %s\n", file);

            //remove all data regarding this file
//            this.removeFile(file);
            this.disk_usage -= fileInfo.getSize() / 1000;

            return "success";
        }
        return "";
    }

    @Override
    public String reclaim(int new_capacity) {

        List<Chunk> stored = new ArrayList<>();
        for (Set<Chunk> chunks : this.storedChunks.values())
            stored.addAll(chunks);

        System.out.println(this.storedChunks);

        while (this.disk_usage > new_capacity) {
            Chunk curr = stored.remove(0); // process first
            this.disk_usage -= curr.getSize() / 1000;

            curr.removeStorage(this.id);

            byte[] message = Message.createMessage(this.version, MessageType.REMOVED, this.id, curr.getFilehash(), curr.getChunkNo());
            controlChannel.multicast(message, message.length);
        }

        return "success";
    }

    public static void main(String[] args) throws IOException{

        if (args.length < 1) {
            System.out.println("usage: Peer <remote_object_name>");
            throw new IOException("Invalid usage");
        }

        String version = args[0];
        int id = Integer.parseInt(args[1]);
        String service_ap = args[2];

        String MC = args[3], MDB = args[4], MDR = args[5];

        Peer peer = new Peer(version, id, MC, MDB, MDR);
        Registry registry = LocateRegistry.getRegistry();
        try {
            ClientPeerProtocol stub = (ClientPeerProtocol) UnicastRemoteObject.exportObject(peer,0);

            //Bind the remote object's stub in the registry
            registry.bind(service_ap, stub); //register peer object with the name in args[0]
        } catch (AlreadyBoundException e) {
            System.out.println("Object already bound! Rebinding...");
            registry.rebind(service_ap, peer);
        }
        System.out.println("Peer " + peer.id + " ready");

        peer.backupChannel.start();
        peer.controlChannel.start();
    }

    public String getVersion() {
        return version;
    }

    public MulticastChannel getBackupChannel() {
        return backupChannel;
    }

    public MulticastChannel getControlChannel() {
        return controlChannel;
    }

    public int getId() {
        return id;
    }

    public Map<String, Set<Chunk>> getStoredChunks() {
        return storedChunks;
    }

    public void addPerceivedReplication(int peer_id, String fileHash, int chunkNo) {
        FileDetails file = this.fileDetails.get(fileHash);
        if (file != null)
            file.addChunkPeer(chunkNo, peer_id);

        Chunk chunk = this.getFileChunk(fileHash, chunkNo);
        if (chunk != null) {
            chunk.addPerceivedRepDegree(); // add perceived degree if chunk exists
        }

        System.out.println("After " + this.storedChunks);
    }

    public void updateLocalChunkReplication(String fileHash, int chunkNo) { // for REMOVED messages

        Chunk chunk = this.getFileChunk(fileHash, chunkNo);
        if (chunk != null) { // decremented the requested chunk's replication degree
            chunk.removePerceivedRepDegree(); // decrement perceived chunk replication

            if (chunk.getPerceivedRepDegree() < chunk.getDesiredRepDegree()) {
                // TODO backup shenanigans
                System.out.println("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            }
        }
    }
    // TODO: Do synchronized stuff
    public void addStoredChunk(Chunk chunk) {
        Set<Chunk> fileChunks = this.storedChunks.computeIfAbsent(
                chunk.getFilehash(),
                l -> new HashSet<>()
        );

        this.disk_usage += chunk.getSize() / 1000; // update current disk space usage

        chunk.addPerceivedRepDegree(); //add chunk perceived replication degree
        fileChunks.add(chunk);
    }

    public Chunk getFileChunk(String fileHash, int chunkNo) {
        //find chunk in stored chunks list
        Set<Chunk> fileChunks = this.storedChunks.get(fileHash);
        Chunk chunk = null;
        for (Chunk c : fileChunks)
            if (c.getChunkNo() == chunkNo)
                chunk = c;

        return chunk;
    }

    public void removeFile(String filename) {
        String hash = this.fileHashes.get(filename).getHash();

        this.fileDetails.remove(hash);
        this.storedChunks.remove(filename);
        this.fileHashes.remove(filename);
    }
}
