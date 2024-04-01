from fastapi import FastAPI, File, Form
import uvicorn
from starlette.requests import Request

import io
import json
import os
from PIL import Image

import numpy as np
import cv2
import base64
import glob
import face_recognition

import dlib
import imutils
from imutils import face_utils
import mediapipe as mp
import numpy as np
from scipy.spatial import distance as dist

# DL Model load
import keras
from keras.models import load_model
from tensorflow.keras.utils import img_to_array


## LOAD MODELS
smile_model = load_model('model/smile_model_GENKI4K.h5')
eye_close_model = load_model('model/model15.h5')

print("Smile Model\n")
smile_model.summary()
print("Blink Model\n")
eye_close_model.summary()

## Face Direction Module
mp_face_mesh = mp.solutions.face_mesh
face_mesh = mp_face_mesh.FaceMesh(min_detection_confidence=0.5, min_tracking_confidence=0.5)

## Mouth Opening Module
# dlib detector, predictor
detector = dlib.get_frontal_face_detector()
predictor = dlib.shape_predictor("model/shape_predictor_68_face_landmarks.dat")

## Face Recognition Module
#
known_face_encondings = []
known_face_names = []
face_database = glob.glob("people_images/*.jpg")
for path in face_database:
    image_name = path.split("/")[1]
    people_name = image_name.split(".")
    if people_name[1] != "jpg":
        continue
    target_image = face_recognition.load_image_file(path)
    target_face_encoding = face_recognition.face_encodings(target_image)[0]
    known_face_encondings.append(target_face_encoding)
    known_face_names.append(people_name[0])


## FUNCTIONS

# CROP FACE
# for smile detection only
def crop_face(image):
    gray = cv2.cvtColor(image, cv2.COLOR_RGB2GRAY)
    face_cascade = cv2.CascadeClassifier('cv2_data/haarcascades/haarcascade_frontalface_default.xml')
    faces = face_cascade.detectMultiScale(gray, scaleFactor=1.3, minNeighbors=3, minSize=(30, 30))
    if len(faces) == 0:
        # No faces detected in the image
        return None
    # Assume there is only one face in the image
    x, y, w, h = faces[0]
    # Crop the face from the image
    face = image[y:y+h, x:x+w]
    # Resize the face to 64x64 pixels
    resized_face = cv2.resize(face, (64, 64))
    return resized_face

def get_both_eyes(img1):
    left_eye_classifier = cv2.CascadeClassifier('cv2_data/haarcascades/haarcascade_lefteye_2splits.xml')
    right_eye_classifier =cv2.CascadeClassifier('cv2_data/haarcascades/haarcascade_righteye_2splits.xml')
    
    crop_img_left = None
    crop_img_right = None
    
    left_eye = left_eye_classifier.detectMultiScale(img1)
    for (ex,ey,ew,eh) in left_eye:
        crop_img_left = img1[ey: ey + eh, ex: ex + ew]
    
    right_eye = right_eye_classifier.detectMultiScale(img1)
    for (ex,ey,ew,eh) in right_eye:
        crop_img_right = img1[ey: ey + eh, ex: ex + ew]
    
    return crop_img_left,crop_img_right

def predict_smile(frame):
    predicted_label = [0]
    if np.shape(frame) == ():
        predicted_label = [0]
    else:
        frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        frame = crop_face(frame)
        frame = np.expand_dims(frame, 0)
        print(frame.shape)
        result = smile_model.predict(frame,steps=1,use_multiprocessing=True,verbose=0).tolist()
        #predicted_label = np.argmax(result,axis=1)
    return int(result[0][1])

def predict_eyes(img, model):
    if np.shape(img) == ():
        return None
    image = cv2.resize(img, (24,24))
    image = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    image = image.astype("float") / 255.0
    image = img_to_array(image)
    image = np.expand_dims(image, axis=0)
    prediction = model.predict(image,verbose = 0)[0]
    #print(prediction)
    if prediction < 0.3:
        prediction = 'closed'
    elif prediction > 0.6:
        prediction = 'open'
    return prediction

def mouth_aspect_ratio(mouth):
	# compute the euclidean distances between the two sets of
	# vertical mouth landmarks (x, y)-coordinates
	A = dist.euclidean(mouth[2], mouth[10]) # 51, 59
	B = dist.euclidean(mouth[4], mouth[8]) # 53, 57

	# compute the euclidean distance between the horizontal
	# mouth landmark (x, y)-coordinates
	C = dist.euclidean(mouth[0], mouth[6]) # 49, 55

	# compute the mouth aspect ratio
	mar = (A + B) / (2.0 * C)

	# return the mouth aspect ratio
	return mar

# define one constants, for mouth aspect ratio to indicate open mouth
MOUTH_AR_THRESH = 0.70
# grab the indexes of the facial landmarks for the mouth
(mStart, mEnd) = (49, 68)

##### FAST API
app = FastAPI()

@app.post("/predict/recognition")
async def face_identity(request: Request):
    image_data = await request.form()
    encoded_image = image_data['image']
    decoded_image = base64.b64decode(encoded_image)
    img = Image.open(io.BytesIO(decoded_image))
    # Convert to RGB if necessary
    if img.mode != 'RGB':
        img = img.convert('RGB')
    
    frame = np.array(img)
    name = "Unknown"
    frame = crop_face(frame)
    encodings = face_recognition.face_encodings(frame)[0]
    matches = face_recognition.compare_faces(known_face_encondings, encodings)
    for x in range(len(matches)):
        if matches[x] == True:
            name = known_face_names[x]
            break
    return name


@app.post("/predict/mouth")
async def mouth_detection(request: Request):
    image_data = await request.form()
    encoded_image = image_data['image']
    decoded_image = base64.b64decode(encoded_image)
    img = Image.open(io.BytesIO(decoded_image))
    # Convert to RGB if necessary
    if img.mode != 'RGB':
        img = img.convert('RGB')
    
    frame = np.array(img)
    grayframe = cv2.cvtColor(frame,cv2.COLOR_BGR2GRAY)
    
    result = 0
    # detect faces in the grayscale frame
    rects = detector(grayframe, 0)
    
    # loop over the face detections
    for rect in rects:
        # determine the facial landmarks for the face region, then
        # convert the facial landmark (x, y)-coordinates to a NumPy
        # array
        shape = predictor(grayframe, rect)
        shape = face_utils.shape_to_np(shape)

        # extract the mouth coordinates, then use the
        # coordinates to compute the mouth aspect ratio
        mouth = shape[mStart:mEnd]
        mouthMAR = mouth_aspect_ratio(mouth)

        # if mouth is open
        if mouthMAR > MOUTH_AR_THRESH:
            result = 1
    return str(result)


@app.post("/predict/smile")
async def smilePredictFunction(request: Request):
    image_data = await request.form()
    encoded_image = image_data['image']
    decoded_image = base64.b64decode(encoded_image)
    img = Image.open(io.BytesIO(decoded_image))
    # Convert to RGB if necessary
    if img.mode != 'RGB':
        img = img.convert('RGB')
    
    frame = np.array(img)
    return str(predict_smile(frame))


@app.post("/predict/blink")
async def faceBlinkFunction(request: Request):
    image_data = await request.form()
    encoded_image = image_data['image']
    decoded_image = base64.b64decode(encoded_image)
    img = Image.open(io.BytesIO(decoded_image))
    # Convert to RGB if necessary
    if img.mode != 'RGB':
        img = img.convert('RGB')
    
    frame = np.array(img)
    
    lefteye,righteye = get_both_eyes(frame)
    
    if predict_eyes(lefteye, eye_close_model) == 'closed' and predict_eyes(righteye, eye_close_model) == 'closed':
        return "1"
    else:
        return "0"

@app.post("/predict/direction")
async def faceDirectionFunction(request: Request):
    image_data = await request.form()
    encoded_image = image_data['image']
    decoded_image = base64.b64decode(encoded_image)
    img = Image.open(io.BytesIO(decoded_image))
    # Convert to RGB if necessary
    if img.mode != 'RGB':
        img = img.convert('RGB')
    
    frame = np.array(img)
    
    thereshold = 7
    # To improve performance
    frame.flags.writeable = False
    
    # Get the result
    results = face_mesh.process(frame)
    
    # To improve performance
    frame.flags.writeable = True
    
    # Convert the color space from RGB to BGR
    #frame = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)

    img_h, img_w, img_c = frame.shape
    face_3d = []
    face_2d = []

    if results.multi_face_landmarks:
        for face_landmarks in results.multi_face_landmarks:
            for idx, lm in enumerate(face_landmarks.landmark):
                if idx == 33 or idx == 263 or idx == 1 or idx == 61 or idx == 291 or idx == 199:
                    if idx == 1:
                        nose_2d = (lm.x * img_w, lm.y * img_h)
                        nose_3d = (lm.x * img_w, lm.y * img_h, lm.z * 3000)

                    x, y = int(lm.x * img_w), int(lm.y * img_h)

                    # Get the 2D Coordinates
                    face_2d.append([x, y])

                    # Get the 3D Coordinates
                    face_3d.append([x, y, lm.z])       
            
            # Convert it to the NumPy array
            face_2d = np.array(face_2d, dtype=np.float64)

            # Convert it to the NumPy array
            face_3d = np.array(face_3d, dtype=np.float64)

            # The camera matrix
            focal_length = 1 * img_w

            cam_matrix = np.array([ [focal_length, 0, img_h / 2],
                                    [0, focal_length, img_w / 2],
                                    [0, 0, 1]])

            # The distortion parameters
            dist_matrix = np.zeros((4, 1), dtype=np.float64)

            # Solve PnP
            success, rot_vec, trans_vec = cv2.solvePnP(face_3d, face_2d, cam_matrix, dist_matrix)

            # Get rotational matrix
            rmat, jac = cv2.Rodrigues(rot_vec)

            # Get angles
            angles, mtxR, mtxQ, Qx, Qy, Qz = cv2.RQDecomp3x3(rmat)

            # Get the y rotation degree
            x = angles[0] * 360
            y = angles[1] * 360
            z = angles[2] * 360
          

            # See where the user's head tilting
            if y < -thereshold:
                direction = "left"
            elif y > thereshold:
                direction = "right"
            elif x < -(thereshold*0.2):
                direction = "down"
            elif x > thereshold:
                direction = "up"
            else:
                direction = "front"
        return direction