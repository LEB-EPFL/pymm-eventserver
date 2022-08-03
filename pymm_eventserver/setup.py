
import os

def install_java_server():

    from qtpy import QtWidgets
    import shutil
    import sys

    app = QtWidgets.QApplication(sys.argv)
    # app.exec()
    mm_folder = QtWidgets.QFileDialog.getExistingDirectory(
        caption="Choose the Micro-Manager main folder"
    )
    plugin_folder = os.path.join(mm_folder, "mmplugins")
    directory = os.path.dirname(__file__)
    files = ["PythonEventServer.jar"]
    for file in files:
        print("src: ", os.path.join(directory, file))
        print("dst: ", os.path.join(plugin_folder, file))
        shutil.copyfile(
            os.path.join(directory, file), os.path.join(plugin_folder, file)
        )