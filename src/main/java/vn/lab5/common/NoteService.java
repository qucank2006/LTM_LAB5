package vn.lab5.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.UUID;

public interface NoteService extends Remote {
  String BINDING = "Notes";

  String ping() throws RemoteException;

  List<Note> list() throws RemoteException;

  Note save(UUID id, String title, String content, long expectedVersion) throws RemoteException;

  Note delete(UUID id, long expectedVersion) throws RemoteException;

  List<Note> snapshot() throws RemoteException;

  void receive(Note note) throws RemoteException;
}
