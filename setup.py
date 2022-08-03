from setuptools import setup

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()

setup(
    name="pymm_eventserver",
    version="0.1.03",
    description="Micro-Manager PythonEventServer",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/leb-epfl/pymm-eventserver",
    project_urls={
        "Bug Tracker": "https://github.com/leb-epfl/pymm-eventserver/issues",
    },
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: BSD License",
        "Operating System :: OS Independent",
    ],
    packages=["pymm_eventserver"],
    package_data={"": ["settings.json", "java/out/artifacts/PythonEventServer.jar"]},
    include_package_data=True,
    install_requires=[
        "pycromanager",
        "qtpy",
    ],
    author="Willi L. Stepp",
    author_email="willi.stepp@epfl.ch",
    python_requires=">=3.7",
)
