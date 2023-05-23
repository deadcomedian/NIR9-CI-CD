FROM python:3.9.16-bullseye
WORKDIR /app
COPY requirements.txt .
COPY app.py .


RUN pip install -r requirements.txt

EXPOSE 5000
ENTRYPOINT [ "python" ]
CMD ["app.py" ]